package com.example.data.repository

import com.example.data.local.TgfDao
import com.example.data.local.TgfChunkEntity
import com.example.data.local.TgfFileEntity
import com.example.domain.model.TgfChunk
import com.example.domain.model.TgfFile
import com.example.domain.repository.TgfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TgfRepositoryImpl(private val tgfDao: TgfDao) : TgfRepository {

    override fun getAllFiles(): Flow<List<TgfFile>> {
        return tgfDao.getAllFilesFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getFileById(fileId: Long): TgfFile? {
        return tgfDao.getFileById(fileId)?.toDomain()
    }

    override fun getChunksForFile(fileId: Long): Flow<List<TgfChunk>> {
        return tgfDao.getChunksForFileFlow(fileId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun insertFile(file: TgfFile): Long {
        return tgfDao.insertFile(file.toEntity())
    }

    override suspend fun insertChunk(chunk: TgfChunk): Long {
        return tgfDao.insertChunk(chunk.toEntity())
    }

    override suspend fun deleteFile(fileId: Long) {
        tgfDao.deleteFileById(fileId)
    }

    // Mapping Extension Functions
    private fun TgfFileEntity.toDomain() = TgfFile(
        id = id,
        name = name,
        size = size,
        totalChunks = totalChunks,
        sha256Hash = sha256Hash,
        isEncrypted = isEncrypted,
        salt = salt,
        uploadDate = uploadDate
    )

    private fun TgfFile.toEntity() = TgfFileEntity(
        id = id,
        name = name,
        size = size,
        totalChunks = totalChunks,
        sha256Hash = sha256Hash,
        isEncrypted = isEncrypted,
        salt = salt,
        uploadDate = uploadDate
    )

    private fun TgfChunkEntity.toDomain() = TgfChunk(
        id = id,
        fileId = fileId,
        messageId = messageId,
        telegramFileId = telegramFileId,
        partIndex = partIndex
    )

    private fun TgfChunk.toEntity() = TgfChunkEntity(
        id = id,
        fileId = fileId,
        messageId = messageId,
        telegramFileId = telegramFileId,
        partIndex = partIndex
    )
}
