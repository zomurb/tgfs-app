package com.zomurb.tgfs.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zomurb.tgfs.data.file.FileChunker
import com.zomurb.tgfs.data.local.TgfDatabase
import com.zomurb.tgfs.data.repository.TgfRepositoryImpl
import com.zomurb.tgfs.data.telegram.TelegramClientImpl
import com.zomurb.tgfs.domain.model.TgfChunk
import com.zomurb.tgfs.domain.model.TgfFile
import com.zomurb.tgfs.domain.repository.TgfRepository
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_FILE_URI = "file_uri"
        const val KEY_PASSWORD_ID = "password_id"
        const val KEY_OUTPUT_FILE_ID = "output_file_id"
        
        private const val CHANNEL_ID = "tgfs_upload_channel"
        private const val NOTIFICATION_ID = 8813
        private const val TAG = "UploadWorker"

        // Secure in-memory RAM cache to avoid serializing plaintext master passwords into WorkManager SQLite database
        private val passwordCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun cachePassword(password: String): String {
            val key = java.util.UUID.randomUUID().toString()
            passwordCache[key] = password
            return key
        }

        fun retrieveAndRemovePassword(key: String): String? {
            return passwordCache.remove(key)
        }
    }

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val database = TgfDatabase.getDatabase(appContext)
    private val repository: TgfRepository = TgfRepositoryImpl(database.tgfDao())
    private val telegramClient = TelegramClientImpl(appContext)
    private val chunker = FileChunker()

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_FILE_URI) ?: return Result.failure()
        val passwordId = inputData.getString(KEY_PASSWORD_ID)
        val masterPassword = passwordId?.let { retrieveAndRemovePassword(it) }
        val fileUri = Uri.parse(uriString)

        createNotificationChannel()
        setForeground(createForegroundInfo(0, "Preparing file chunking..."))

        val createdTempFiles = mutableListOf<File>()

        try {
            Log.d(TAG, "Starting storage processing for URI: $fileUri")

            // 1. Process and chunk (and optionally encrypt) the file
            val chunkResult = chunker.processUri(
                context = applicationContext,
                uri = fileUri,
                masterPassword = masterPassword,
                onProgress = { chunkIdx, percent ->
                    // Optional local logging for chunk generation progress
                }
            )

            createdTempFiles.addAll(chunkResult.tempChunkFiles)
            val totalParts = chunkResult.totalChunks

            if (totalParts == 0) {
                Log.e(TAG, "Chunking resulted in zero temporary chunks.")
                showFailedNotification("Processing yielded no data.")
                return Result.failure()
            }

            Log.d(TAG, "Success processing file! Created $totalParts secure chunks.")

            // 2. Save the Master File Record in Room (initially marked with total parts)
            val baseFile = TgfFile(
                name = chunkResult.originalName,
                size = chunkResult.originalSize,
                totalChunks = totalParts,
                sha256Hash = chunkResult.sha256Hash,
                isEncrypted = chunkResult.isEncrypted,
                salt = chunkResult.salt,
                uploadDate = System.currentTimeMillis()
            )
            val fileId = repository.insertFile(baseFile)

            // 3. Parallel upload block limiting concurrency to 2 parallel threads to prevent OutOfMemoryError
            val semaphore = kotlinx.coroutines.sync.Semaphore(2)
            val totalPartsCount = totalParts
            val progressMap = java.util.concurrent.ConcurrentHashMap<Int, Float>()

            kotlinx.coroutines.coroutineScope {
                val jobs = chunkResult.tempChunkFiles.mapIndexed { idx, tempFile ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        semaphore.withPermit {
                            Log.d(TAG, "Starting parallel upload chunk Part ${idx + 1}/$totalParts")
                            
                            // Initialize this part's progress
                            progressMap[idx] = 0f

                            // Upload chunk to TG Saved Messages via Bot API/TDLib wrapper
                            val uploadResult = telegramClient.uploadChunk(
                                chunkFile = tempFile,
                                partIndex = idx,
                                totalParts = totalPartsCount,
                                onProgress = { prg ->
                                    progressMap[idx] = prg
                                    
                                    // Calculate overall dynamic progress
                                    val sumProgress = progressMap.values.sum()
                                    val overallProgressPercent = (sumProgress / (totalPartsCount * 100f)) * 100f
                                    
                                    notificationManager.notify(
                                        NOTIFICATION_ID,
                                        createNotification(
                                            progress = overallProgressPercent.toInt(),
                                            text = "Uploading: ${chunkResult.originalName} (${overallProgressPercent.toInt()}%)"
                                        )
                                    )
                                }
                            )

                            // Write individual chunk association record to Local DB
                            val chunkRecord = TgfChunk(
                                fileId = fileId,
                                messageId = uploadResult.messageId,
                                telegramFileId = uploadResult.telegramFileId,
                                partIndex = idx
                            )
                            repository.insertChunk(chunkRecord)

                            // 4. Memory/Storage Conservation: immediately release local file space
                            if (tempFile.exists()) {
                                tempFile.delete()
                                Log.d(TAG, "Deleted parallel local temp file chunk successfully: ${tempFile.name}")
                            }
                        }
                    }
                }
                
                // Block/Wait for all concurrent chunk uploads to complete
                jobs.awaitAll()
            }

            // Trigger automated post-upload database metadata synchronization backup
            try {
                Log.d(TAG, "Triggering automatic database metadata sync post-upload...")
                com.zomurb.tgfs.data.local.DatabaseSyncManager.syncDatabase(applicationContext)
            } catch (syncEx: Exception) {
                Log.e(TAG, "Failed backing up database after upload", syncEx)
            }

            // Completed! Show success notification
            showSuccessNotification("Uploaded: ${chunkResult.originalName}")
            
            return Result.success(workDataOf(KEY_OUTPUT_FILE_ID to fileId))

        } catch (e: Exception) {
            Log.e(TAG, "Upload execution crashed", e)
            showFailedNotification(e.localizedMessage ?: "Unknown transmission failure")
            
            // Clean up left-over cache files to prevent memory leak
            createdTempFiles.forEach { file ->
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Cleanup: Deleted residual local temp chunk ${file.name}")
                }
            }
            return Result.failure()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "TGFS Network Transfers"
            val descriptionText = "Notifications for TGFS file background chunk processes"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(progress: Int, text: String): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                createNotification(progress, text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, createNotification(progress, text))
        }
    }

    private fun createNotification(progress: Int, text: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("TGFS Cloud Storage Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showSuccessNotification(fileName: String) {
        val successNotify = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("TGFS Asset Saved Successfully")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, successNotify)
    }

    private fun showFailedNotification(reason: String) {
        val failNotify = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("TGFS Upload Failed")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 2, failNotify)
    }
}
