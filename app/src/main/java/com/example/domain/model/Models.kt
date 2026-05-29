package com.example.domain.model

data class TgfFile(
    val id: Long = 0,
    val name: String,
    val size: Long,
    val totalChunks: Int,
    val sha256Hash: String,
    val isEncrypted: Boolean,
    val salt: String?,
    val uploadDate: Long = System.currentTimeMillis()
)

data class TgfChunk(
    val id: Long = 0,
    val fileId: Long,
    val messageId: Long,
    val telegramFileId: String,
    val partIndex: Int
)
