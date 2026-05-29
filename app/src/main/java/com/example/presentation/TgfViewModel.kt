package com.example.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.TgfDatabase
import com.example.data.repository.TgfRepositoryImpl
import com.example.data.telegram.TelegramClientImpl
import com.example.data.worker.UploadWorker
import com.example.domain.model.TgfChunk
import com.example.domain.model.TgfFile
import com.example.domain.repository.TelegramClient
import com.example.domain.repository.TgfRepository
import java.io.File
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TgfViewModel(application: Application) : AndroidViewModel(application) {

    private val database = TgfDatabase.getDatabase(application)
    private val repository: TgfRepository = TgfRepositoryImpl(database.tgfDao())
    private val telegramClient: TelegramClient = TelegramClientImpl(application)
    private val workManager: WorkManager? = try {
        WorkManager.getInstance(application)
    } catch (e: Exception) {
        android.util.Log.e("TgfViewModel", "WorkManager is not initialized yet", e)
        null
    }

    // Reactive streams mapping State & Domain elements
    val authState: StateFlow<TelegramClient.AuthState> = telegramClient.authState

    val filesState: StateFlow<List<TgfFile>> = repository.getAllFiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val workInfos: Flow<List<WorkInfo>> = workManager?.getWorkInfosForUniqueWorkFlow("tgfs_upload") ?: flowOf(emptyList())

    init {
        viewModelScope.launch {
            telegramClient.authState.collect { state ->
                if (state is TelegramClient.AuthState.Authorized) {
                    val files = repository.getAllFiles().first()
                    if (files.isEmpty()) {
                        val prefs = getApplication<Application>().getSharedPreferences("tgfs_prefs", android.content.Context.MODE_PRIVATE)
                        val savedPw = prefs.getString("master_password", "") ?: ""
                        if (savedPw.isNotBlank()) {
                            android.util.Log.d("TgfViewModel", "TDLib login complete, DB index is empty. Triggering automated Zero-Knowledge cloud recovery...")
                            triggerCloudRecovery(
                                context = getApplication(),
                                masterPassword = savedPw,
                                onStatus = { msg -> android.util.Log.d("TgfViewModel", "Recovery state: $msg") },
                                onResult = { succ, msg -> android.util.Log.d("TgfViewModel", "Recovery ended (success=$succ): $msg") }
                            )
                        }
                    }
                }
            }
        }
    }

    fun triggerCloudRecovery(
        context: android.content.Context,
        masterPassword: String,
        onStatus: (String) -> Unit,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                onStatus("Поиск индексов в Telegram Saved Messages...")
                val fileId = telegramClient.findLatestBackup()
                if (fileId == null) {
                    onResult(false, "Резервная копия базы данных не обнаружена в Saved Messages.")
                    return@launch
                }

                onStatus("Скачивание резервной копии...")
                val tempBackupFile = telegramClient.downloadChunk(
                    telegramFileId = fileId,
                    partIndex = 0,
                    totalParts = 1,
                    onProgress = { prg ->
                        onStatus("Скачивание резервной копии (${prg.toInt()}%)")
                    }
                )

                onStatus("Дешифрование индексов...")
                com.example.data.local.TgfDatabase.restoreFromBackup(
                    context = context,
                    backupFile = tempBackupFile,
                    masterPassword = masterPassword
                )

                if (tempBackupFile.exists()) {
                    tempBackupFile.delete()
                }

                onResult(true, "База данных успешно восстановлена!")
            } catch (e: Exception) {
                android.util.Log.e("TgfViewModel", "Cloud recovery crashed", e)
                onResult(false, "Сбой восстановления: ${e.localizedMessage ?: "Неверный мастер-пароль или файл поврежден"}")
            }
        }
    }

    fun loginWithPhone(phone: String) {
        viewModelScope.launch {
            try {
                telegramClient.sendPhoneNumber(phone)
            } catch (e: Exception) {
                // handle
            }
        }
    }

    fun loginWithCode(code: String) {
        viewModelScope.launch {
            try {
                telegramClient.sendCode(code)
            } catch (e: Exception) {
                // handle
            }
        }
    }

    fun loginWithPassword(password: String) {
        viewModelScope.launch {
            try {
                telegramClient.sendPassword(password)
            } catch (e: Exception) {
                // handle
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            telegramClient.logout()
        }
    }

    fun startUpload(uri: Uri, isEncryptionEnabled: Boolean, masterPassword: String?) {
        val passwordId = if (isEncryptionEnabled && !masterPassword.isNullOrBlank()) {
            UploadWorker.cachePassword(masterPassword)
        } else {
            null
        }

        val inputData = workDataOf(
            UploadWorker.KEY_FILE_URI to uri.toString(),
            UploadWorker.KEY_PASSWORD_ID to passwordId
        )

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .build()

        workManager?.enqueue(uploadRequest)
    }

    fun deleteFileRecord(fileId: Long) {
        viewModelScope.launch {
            try {
                val chunks = repository.getChunksForFile(fileId).first()
                for (chunk in chunks) {
                    telegramClient.deleteMessage(chunk.messageId)
                }
            } catch (e: Exception) {
                android.util.Log.e("TgfViewModel", "Failed to delete remote Telegram parts for file $fileId", e)
            } finally {
                repository.deleteFile(fileId)
                try {
                    android.util.Log.d("TgfViewModel", "Triggering automatic database metadata sync post-delete...")
                    com.example.data.local.DatabaseSyncManager.syncDatabase(getApplication())
                } catch (syncEx: Exception) {
                    android.util.Log.e("TgfViewModel", "Failed database sync post-delete", syncEx)
                }
            }
        }
    }

    // Fetches static chunk details for selected room file
    fun getChunksForFile(fileId: Long): Flow<List<TgfChunk>> {
        return repository.getChunksForFile(fileId)
    }

    fun downloadAndAssembleFile(
        file: TgfFile,
        password: String?,
        context: android.content.Context,
        onProgress: (String) -> Unit,
        onCompleted: (String) -> Unit,
        onFailed: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                onProgress("Получение частей файла из базы данных...")
                val chunks = repository.getChunksForFile(file.id).first()
                if (chunks.isEmpty()) {
                    onFailed("Для этого файла не зарегистрированы части в базе данных!")
                    return@launch
                }

                val tempFiles = mutableListOf<File>()
                val sortedChunks = chunks.sortedBy { it.partIndex }

                for (chunk in sortedChunks) {
                    onProgress("Скачивание части файла ${chunk.partIndex + 1} из ${chunks.size}...")
                    val downloadedFile = telegramClient.downloadChunk(
                        telegramFileId = chunk.telegramFileId,
                        partIndex = chunk.partIndex,
                        totalParts = chunks.size,
                        onProgress = { prg ->
                            onProgress("Скачивание части файла ${chunk.partIndex + 1}/${chunks.size} (${prg.toInt()}%)")
                        }
                    )
                    tempFiles.add(downloadedFile)
                }

                onProgress("Расшифрование и объединение частей...")
                val stagingFile = File(context.cacheDir, "tgfs_staging_${System.currentTimeMillis()}.bin")

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (file.isEncrypted) {
                            if (password.isNullOrBlank()) {
                                throw IllegalArgumentException("Для разблокировки этого файла требуется мастер-пароль.")
                            }
                            decryptAndMergeChunks(tempFiles, stagingFile, password, file.salt)
                        } else {
                            mergeChunks(tempFiles, stagingFile)
                        }

                        // Verify file integrity check!
                        onProgress("Проверка целостности файла (SHA-256)...")
                        val actualHash = calculateSha256(stagingFile)
                        if (actualHash != file.sha256Hash) {
                            if (stagingFile.exists()) {
                                stagingFile.delete()
                            }
                            throw java.io.IOException("Ошибка проверки целостности файла (несовпадение хэш-суммы SHA-256). Восстановленный файл удален, так как он может быть поврежден.")
                        }

                        // Save using MediaStore Downloads API (Android 10+ compatible)
                        onProgress("Сохранение файла в системные загрузки...")
                        saveToDownloadsMediaStore(context, stagingFile, file.name)
                    } finally {
                        if (stagingFile.exists()) {
                            stagingFile.delete()
                        }
                        // Clean intermediate files
                        tempFiles.forEach {
                            if (it.exists()) {
                                it.delete()
                            }
                        }
                    }
                }

                onCompleted("Сохранено в загрузки: ${file.name}")
            } catch (e: Exception) {
                android.util.Log.e("TgfViewModel", "Reconstruction failed", e)
                onFailed(e.localizedMessage ?: "Не удалось восстановить файл из частей.")
            }
        }
    }

    private fun saveToDownloadsMediaStore(context: android.content.Context, sourceFile: File, fileName: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver

            // Check if an existing file with the same name exists in Downloads, if so remove prior entry
            val existingUri = findExistingDownload(context, fileName)
            if (existingUri != null) {
                try {
                    resolver.delete(existingUri, null, null)
                } catch (e: Exception) {
                    // Ignore delete errors for older entries
                }
            }

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, getMimeType(fileName))
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw java.io.IOException("MediaStore: failed to create entry for $fileName")

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: throw java.io.IOException("MediaStore: could not open output stream for $fileName")

                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 9 or lower fallback using standard File APIs
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val outputFile = File(downloadsDir, fileName)
            sourceFile.inputStream().use { inputStream ->
                outputFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    private fun findExistingDownload(context: android.content.Context, fileName: String): android.net.Uri? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return null
        }
        val resolver = context.contentResolver
        val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
        val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        resolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID))
                return android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
            }
        }
        return null
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun mergeChunks(tempFiles: List<File>, finalFile: File) {
        finalFile.outputStream().use { fos ->
            tempFiles.forEach { tempFile ->
                tempFile.inputStream().use { fis ->
                    fis.copyTo(fos)
                }
            }
        }
    }

    private fun decryptAndMergeChunks(tempFiles: List<File>, finalFile: File, password: String, saltHex: String?) {
        val saltBytes = saltHex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            ?: throw IllegalArgumentException("Salt is missing for encrypted file structure.")

        // Derive PBKDF2 Key (same as Chunker uses) matches Python iterations of 100000
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), saltBytes, 100000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(spec).encoded
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(derived, "AES")

        java.io.BufferedOutputStream(finalFile.outputStream(), 128 * 1024).use { fos ->
            tempFiles.forEach { tempFile ->
                val fileLength = tempFile.length()
                if (fileLength < 12) {
                    throw java.io.IOException("Corrupt chunk: file size is too small (${fileLength} bytes).")
                }
                
                tempFile.inputStream().use { fis ->
                    // 1. Read IV (12 bytes)
                    val iv = ByteArray(12)
                    var bytesReadIv = 0
                    while (bytesReadIv < 12) {
                        val read = fis.read(iv, bytesReadIv, 12 - bytesReadIv)
                        if (read == -1) break
                        bytesReadIv += read
                    }
                    if (bytesReadIv != 12) {
                        throw java.io.IOException("Corrupt chunk: IV is $bytesReadIv bytes, expected 12.")
                    }

                    // 2. Read the remaining bytes (ciphertext + tag)
                    val ciphertextLength = (fileLength - 12).toInt()
                    val ciphertext = ByteArray(ciphertextLength)
                    var totalRead = 0
                    while (totalRead < ciphertextLength) {
                        val read = fis.read(ciphertext, totalRead, ciphertextLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    if (totalRead != ciphertextLength) {
                        throw java.io.IOException("Corrupt chunk: expected $ciphertextLength ciphertext bytes, but read $totalRead.")
                    }

                    // 3. Initialize Cipher in DECRYPT_MODE
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        secretKeySpec,
                        javax.crypto.spec.GCMParameterSpec(128, iv)
                    )

                    // 4. Decrypt and write to output stream
                    val plaintext = cipher.doFinal(ciphertext)
                    fos.write(plaintext)
                }
            }
        }
    }
}
