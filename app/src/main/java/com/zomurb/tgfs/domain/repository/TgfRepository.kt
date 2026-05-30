package com.zomurb.tgfs.domain.repository

import com.zomurb.tgfs.domain.model.TgfChunk
import com.zomurb.tgfs.domain.model.TgfFile
import kotlinx.coroutines.flow.Flow

interface TgfRepository {
    fun getAllFiles(): Flow<List<TgfFile>>
    suspend fun getFileById(fileId: Long): TgfFile?
    fun getChunksForFile(fileId: Long): Flow<List<TgfChunk>>
    suspend fun insertFile(file: TgfFile): Long
    suspend fun insertChunk(chunk: TgfChunk): Long
    suspend fun deleteFile(fileId: Long)
}
