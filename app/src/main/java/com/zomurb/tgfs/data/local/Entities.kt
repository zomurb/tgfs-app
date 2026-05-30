package com.zomurb.tgfs.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "files")
data class TgfFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val size: Long,
    @ColumnInfo(name = "total_chunks") val totalChunks: Int,
    @ColumnInfo(name = "sha256_hash") val sha256Hash: String,
    @ColumnInfo(name = "is_encrypted") val isEncrypted: Boolean,
    val salt: String?,
    @ColumnInfo(name = "upload_date") val uploadDate: Long
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = TgfFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["file_id"])]
)
data class TgfChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "telegram_file_id") val telegramFileId: String = "",
    @ColumnInfo(name = "part_index") val partIndex: Int
)
