package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

@Database(
    entities = [TgfFileEntity::class, TgfChunkEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TgfDatabase : RoomDatabase() {
    abstract fun tgfDao(): TgfDao

    companion object {
        @Volatile
        private var INSTANCE: TgfDatabase? = null

        fun getDatabase(context: Context): TgfDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TgfDatabase::class.java,
                    "tgfs_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Safely closes the active DB helper connection, wipes existing files, decrypts
         * the cloud backup, overwrites, and re-initializes Room state.
         */
        fun restoreFromBackup(context: Context, backupFile: File, masterPassword: String?) {
            synchronized(this) {
                // 1. Terminate current Room connections
                android.util.Log.d("TgfDatabase", "Closing active Room instance before restoration...")
                INSTANCE?.close()
                INSTANCE = null

                val dbFile = context.getDatabasePath("tgfs_database")
                val walFile = context.getDatabasePath("tgfs_database-wal")
                val shmFile = context.getDatabasePath("tgfs_database-shm")

                // 2. Wipe current indices strictly
                if (dbFile.exists()) dbFile.delete()
                if (walFile.exists()) walFile.delete()
                if (shmFile.exists()) shmFile.delete()

                // 3. Overwrite with decrypted cloud file
                if (!masterPassword.isNullOrBlank()) {
                    DatabaseSyncManager.decryptDbFile(backupFile, dbFile, masterPassword)
                } else {
                    backupFile.inputStream().use { input ->
                        dbFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                android.util.Log.d("TgfDatabase", "Re-opening newly restored SQLite file pool...")
                // 4. Force re-compiling connection properties
                getDatabase(context)
            }
        }
    }
}
