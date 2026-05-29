package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TgfDao {
    @Query("SELECT * FROM files ORDER BY upload_date DESC")
    fun getAllFilesFlow(): Flow<List<TgfFileEntity>>

    @Query("SELECT * FROM files ORDER BY upload_date DESC")
    suspend fun getAllFiles(): List<TgfFileEntity>

    @Query("SELECT * FROM files WHERE id = :fileId")
    suspend fun getFileById(fileId: Long): TgfFileEntity?

    @Query("SELECT * FROM chunks WHERE file_id = :fileId ORDER BY part_index ASC")
    fun getChunksForFileFlow(fileId: Long): Flow<List<TgfChunkEntity>>

    @Query("SELECT * FROM chunks WHERE file_id = :fileId ORDER BY part_index ASC")
    suspend fun getChunksForFile(fileId: Long): List<TgfChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: TgfFileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: TgfChunkEntity): Long

    @Query("DELETE FROM files WHERE id = :fileId")
    suspend fun deleteFileById(fileId: Long)
}
