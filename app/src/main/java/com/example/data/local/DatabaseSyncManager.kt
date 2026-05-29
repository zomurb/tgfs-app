package com.example.data.local

import android.content.Context
import android.util.Log
import com.example.data.telegram.TelegramClientImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object DatabaseSyncManager {
    private const val TAG = "DatabaseSyncManager"
    private const val BACKUP_PREFIX = "TGFS_METADATA_BACKUP_v1_"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Forces checkpointing of Room database, exports, optionally encrypts,
     * and uploads the database backup file to Telegram.
     */
    suspend fun syncDatabase(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating database backup sync...")

            // 1. Flush/Checkpoint Room WAL to standard DB file
            val db = TgfDatabase.getDatabase(context)
            try {
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                    if (cursor.moveToFirst()) {
                        Log.d(TAG, "Room database checkpoint completed successfully.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed checkpointing database, attempting standard copy.", e)
            }

            // 2. Fetch connection configurations
            val prefs = context.getSharedPreferences("tgfs_prefs", Context.MODE_PRIVATE)
            val botToken = prefs.getString("bot_token", "") ?: ""
            val chatId = prefs.getString("chat_id", "") ?: ""
            val masterPassword = prefs.getString("master_password", "") ?: ""

            if (botToken.isBlank() || chatId.isBlank()) {
                Log.w(TAG, "Database sync skipped: Bot configuration is incomplete.")
                return@withContext
            }

            // 3. Obtain database path
            val dbFile = context.getDatabasePath("tgfs_database")
            if (!dbFile.exists()) {
                Log.e(TAG, "Database file does not exist at path: ${dbFile.absolutePath}")
                return@withContext
            }

            val timestamp = System.currentTimeMillis()
            val backupFileName = "tgfs_backup_$timestamp.db"
            val backupTempFile = File(context.cacheDir, backupFileName)

            val isEncrypted = masterPassword.isNotBlank()

            if (isEncrypted) {
                Log.d(TAG, "Encrypting Room database backup under master password...")
                encryptDbFile(dbFile, backupTempFile, masterPassword)
            } else {
                Log.d(TAG, "Copying Room database backup as plaintext...")
                dbFile.inputStream().use { input ->
                    backupTempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // 4. Send document request using Bot API
            val caption = "$BACKUP_PREFIX$timestamp"
            val filePart = backupTempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("document", backupFileName, filePart)
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendDocument")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed uploading DB backup: ${response.code} - $responseBody")
                } else {
                    Log.d(TAG, "Database backup uploaded successfully! Caption: $caption")
                    try {
                        val json = org.json.JSONObject(responseBody)
                        if (json.optBoolean("ok", false)) {
                            val result = json.optJSONObject("result")
                            val messageId = result?.optLong("message_id", -1L) ?: -1L
                            if (messageId != -1L) {
                                // Find any previous pinned backup message via getChat
                                var oldPinnedMessageId: Long = -1L
                                try {
                                    val getChatUrl = "https://api.telegram.org/bot$botToken/getChat?chat_id=$chatId"
                                    val getChatRequest = Request.Builder().url(getChatUrl).get().build()
                                    httpClient.newCall(getChatRequest).execute().use { chatResponse ->
                                        if (chatResponse.isSuccessful) {
                                            val chatBody = chatResponse.body?.string() ?: ""
                                            val chatJson = org.json.JSONObject(chatBody)
                                            if (chatJson.optBoolean("ok", false)) {
                                                val chatResult = chatJson.optJSONObject("result")
                                                val pinnedMessage = chatResult?.optJSONObject("pinned_message")
                                                if (pinnedMessage != null) {
                                                    val pinCaption = pinnedMessage.optString("caption", "")
                                                    if (pinCaption.startsWith(BACKUP_PREFIX)) {
                                                        oldPinnedMessageId = pinnedMessage.optLong("message_id", -1L)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error looking up pinned message during sync", e)
                                }

                                // Also check if we have a locally stored previous message ID as a fallback, just in case
                                val oldStoredMessageId = prefs.getLong("last_backup_message_id", -1L)
                                val idsToDelete = mutableSetOf<Long>()
                                if (oldPinnedMessageId != -1L && oldPinnedMessageId != messageId) {
                                    idsToDelete.add(oldPinnedMessageId)
                                }
                                if (oldStoredMessageId != -1L && oldStoredMessageId != messageId) {
                                    idsToDelete.add(oldStoredMessageId)
                                }

                                // Deleting the previous backups
                                for (oldId in idsToDelete) {
                                    try {
                                        Log.d(TAG, "Deleting old backup message ID: $oldId")
                                        val deleteUrl = "https://api.telegram.org/bot$botToken/deleteMessage?chat_id=$chatId&message_id=$oldId"
                                        val deleteRequest = Request.Builder().url(deleteUrl).get().build()
                                        httpClient.newCall(deleteRequest).execute().use { delResponse ->
                                            if (delResponse.isSuccessful) {
                                                Log.d(TAG, "Successfully deleted old backup message: $oldId")
                                            } else {
                                                Log.w(TAG, "Failed to delete old backup message $oldId: ${delResponse.code}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed deleting old backup message $oldId", e)
                                    }
                                }

                                // Save the new backup message ID to local SharedPreferences
                                prefs.edit().putLong("last_backup_message_id", messageId).apply()

                                // Pin the newly created backup message
                                Log.d(TAG, "Pinning the latest backup message ID: $messageId in chat: $chatId")
                                val pinUrl = "https://api.telegram.org/bot$botToken/pinChatMessage?chat_id=$chatId&message_id=$messageId&disable_notification=true"
                                val pinRequest = Request.Builder().url(pinUrl).get().build()
                                httpClient.newCall(pinRequest).execute().use { pinResponse ->
                                    if (pinResponse.isSuccessful) {
                                        Log.d(TAG, "Successfully pinned metadata backup message: $messageId")
                                    } else {
                                        Log.w(TAG, "Failed pinning metadata backup message: ${pinResponse.code}")
                                    }
                                }
                            }
                        }
                    } catch (pinEx: Exception) {
                        Log.e(TAG, "Failed in clean-up and pinning process", pinEx)
                    }
                }
            }

            // Clean up cache file
            if (backupTempFile.exists()) {
                backupTempFile.delete()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during database sync backup", e)
        }
    }

    /**
     * Encrypts the source database file using PBKDF2/AES-GCM key derived from Master Password.
     */
    private fun encryptDbFile(inputFile: File, outputFile: File, password: String) {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }

        val spec = PBEKeySpec(password.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(spec).encoded
        val secretKeySpec = SecretKeySpec(derived, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(128, iv))

        outputFile.outputStream().use { fos ->
            fos.write(salt)
            fos.write(iv)
            val plainBytes = inputFile.readBytes()
            val cipherBytes = cipher.doFinal(plainBytes)
            fos.write(cipherBytes)
        }
    }

    /**
     * Decrypts the DB backup file using specified master password.
     */
    fun decryptDbFile(inputFile: File, outputFile: File, password: String) {
        inputFile.inputStream().use { fis ->
            val salt = ByteArray(16)
            var readSize = fis.read(salt)
            if (readSize != 16) throw java.io.IOException("Invalid metadata backup header: Salt is truncated.")

            val iv = ByteArray(12)
            readSize = fis.read(iv)
            if (readSize != 12) throw java.io.IOException("Invalid metadata backup header: IV is truncated.")

            val spec = PBEKeySpec(password.toCharArray(), salt, 100000, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val derived = factory.generateSecret(spec).encoded
            val secretKeySpec = SecretKeySpec(derived, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(128, iv))

            val cipherBytes = fis.readBytes()
            val plainBytes = cipher.doFinal(cipherBytes)
            outputFile.writeBytes(plainBytes)
        }
    }
}
