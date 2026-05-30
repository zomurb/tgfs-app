package com.zomurb.tgfs.domain.repository

import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface TelegramClient {
    sealed interface AuthState {
        object Uninitialized : AuthState
        object PhoneNumberRequired : AuthState
        data class Handshaking(val stage: String, val progress: Float, val logs: List<String>) : AuthState
        object CodeRequired : AuthState
        object PasswordRequired : AuthState // 2FA
        object Authorized : AuthState
        data class Error(val message: String) : AuthState
    }

    val authState: StateFlow<AuthState>

    suspend fun sendPhoneNumber(phoneNumber: String)
    suspend fun sendCode(code: String)
    suspend fun sendPassword(password: String)
    suspend fun logout()

    data class UploadResult(val messageId: Long, val telegramFileId: String)

    /**
     * Uploads an encrypted/plaintext chunk to the personal chat in Telegram.
     * Returns the UploadResult mapping both Message ID and File ID.
     */
    suspend fun uploadChunk(
        chunkFile: File,
        partIndex: Int,
        totalParts: Int,
        onProgress: (Float) -> Unit
    ): UploadResult

    /**
     * Downloads a chunk from Telegram using the Bot API file_id.
     * Saves it to a local temporary file and returns it.
     */
    suspend fun downloadChunk(
        telegramFileId: String,
        partIndex: Int,
        totalParts: Int,
        onProgress: (Float) -> Unit
    ): File

    /**
     * Deletes an uploaded message/chunk from Telegram chat.
     */
    suspend fun deleteMessage(messageId: Long)

    /**
     * Queries the user's chat messages for the latest database backups.
     * Returns the file_id if found.
     */
    suspend fun findLatestBackup(): String?
}
