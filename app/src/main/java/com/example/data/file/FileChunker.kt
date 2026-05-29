package com.example.data.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class FileChunker {

    companion object {
        const val CHUNK_SIZE = 48 * 1024 * 1024 // 48 MB in bytes (compatible with Python CLI logic)
        private const val BUFFER_SIZE = 128 * 1024  // 128 KB buffer for reading/writing streams
        private const val TAG = "FileChunker"

        // Cryptographic constants
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_LENGTH = 256
        private const val ITERATIONS = 100000
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }

    data class ChunkResult(
        val originalName: String,
        val originalSize: Long,
        val totalChunks: Int,
        val sha256Hash: String,
        val isEncrypted: Boolean,
        val salt: String?,
        val tempChunkFiles: List<File>
    )

    /**
     * Splits a file from an Android Uri into 48 MB chunks, optionally applying AES-GCM
     * encryption without loading the entire chunk/file into RAM.
     */
    fun processUri(
        context: Context,
        uri: Uri,
        masterPassword: String?,
        onProgress: (chunkIndex: Int, progressPercent: Float) -> Unit = { _, _ -> }
    ): ChunkResult {
        val contentResolver = context.contentResolver
        val (fileName, fileSize) = getFileMetadata(context, uri)
        
        val isEncrypted = !masterPassword.isNullOrBlank()
        val secureRandom = SecureRandom()
        
        // Generate Salt if encrypting
        val saltBytes = if (isEncrypted) {
            ByteArray(16).apply { secureRandom.nextBytes(this) }
        } else {
            null
        }
        val saltHex = saltBytes?.joinToString("") { String.format("%02x", it) }

        // Derive Key if encrypting
        val secretKeySpec = if (isEncrypted && masterPassword != null && saltBytes != null) {
            deriveKey(masterPassword, saltBytes)
        } else {
            null
        }

        val fileDigest = MessageDigest.getInstance("SHA-256")
        val tempChunkFiles = mutableListOf<File>()
        
        var totalBytesRead: Long = 0
        var chunkIndex = 0

        contentResolver.openInputStream(uri)?.use { rawInputStream ->
            BufferedInputStream(rawInputStream).use { bufferedInputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var endOfFileReached = false

                while (!endOfFileReached) {
                    val tempChunkFile = File(context.cacheDir, "tgfs_chunk_${System.currentTimeMillis()}_$chunkIndex.bin")
                    var currentChunkBytesWritten = 0L

                    if (isEncrypted && secretKeySpec != null) {
                        // AES-GCM encryption mode
                        // 1. Generate unique 12-byte IV for this chunk
                        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
                        
                        // 2. Initialise Cipher
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

                        FileOutputStream(tempChunkFile).use { fos ->
                            // Write IV to the start of the chunk file (12 bytes)
                            fos.write(iv)

                            // Read up to 48MB from original stream, hash it, encrypt it, and write
                            while (currentChunkBytesWritten < CHUNK_SIZE) {
                                val bytesToRead = minOf(
                                    BUFFER_SIZE.toLong(),
                                    CHUNK_SIZE - currentChunkBytesWritten
                                ).toInt()
                                
                                val bytesRead = bufferedInputStream.read(buffer, 0, bytesToRead)
                                if (bytesRead == -1) {
                                    endOfFileReached = true
                                    break
                                }

                                // Update file SHA-256 hash with raw plaintext bytes
                                fileDigest.update(buffer, 0, bytesRead)
                                
                                // Encrypt the buffer block
                                val encryptedBlock = cipher.update(buffer, 0, bytesRead)
                                if (encryptedBlock != null && encryptedBlock.isNotEmpty()) {
                                    fos.write(encryptedBlock)
                                }

                                currentChunkBytesWritten += bytesRead
                                totalBytesRead += bytesRead
                                
                                val chunkProgress = (currentChunkBytesWritten.toFloat() / CHUNK_SIZE.toFloat()) * 100
                                onProgress(chunkIndex, chunkProgress)
                            }
                            
                            // Finalise encryption tag and write final block
                            val finalBlock = cipher.doFinal()
                            if (finalBlock != null && finalBlock.isNotEmpty()) {
                                fos.write(finalBlock)
                            }
                        }
                    } else {
                        // Plaintext/No encryption mode
                        FileOutputStream(tempChunkFile).use { fos ->
                            while (currentChunkBytesWritten < CHUNK_SIZE) {
                                val bytesToRead = minOf(
                                    BUFFER_SIZE.toLong(),
                                    CHUNK_SIZE - currentChunkBytesWritten
                                ).toInt()

                                val bytesRead = bufferedInputStream.read(buffer, 0, bytesToRead)
                                if (bytesRead == -1) {
                                    endOfFileReached = true
                                    break
                                }

                                // Update file SHA-256 hash with raw bytes
                                fileDigest.update(buffer, 0, bytesRead)
                                
                                // Write raw bytes directly
                                fos.write(buffer, 0, bytesRead)

                                currentChunkBytesWritten += bytesRead
                                totalBytesRead += bytesRead
                                
                                val chunkProgress = (currentChunkBytesWritten.toFloat() / CHUNK_SIZE.toFloat()) * 100
                                onProgress(chunkIndex, chunkProgress)
                            }
                        }
                    }

                    // Only add the file to the checklist if bytes were actually written
                    if (tempChunkFile.length() > 0) {
                        tempChunkFiles.add(tempChunkFile)
                        chunkIndex++
                    } else {
                        tempChunkFile.delete()
                    }
                    
                    if (totalBytesRead >= fileSize && fileSize > 0) {
                        break
                    }
                }
            }
        } ?: throw IllegalArgumentException("Could not open InputStream from Uri: $uri")

        val finalSha256 = fileDigest.digest().joinToString("") { String.format("%02x", it) }

        return ChunkResult(
            originalName = fileName,
            originalSize = totalBytesRead,
            totalChunks = tempChunkFiles.size,
            sha256Hash = finalSha256,
            isEncrypted = isEncrypted,
            salt = saltHex,
            tempChunkFiles = tempChunkFiles
        )
    }

    /**
     * Gets file name and size metadata from content Uri.
     */
    private fun getFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment ?: "shared_file_${System.currentTimeMillis()}"
        }
        return Pair(name, size)
    }

    /**
     * Derives a cryptographically strong 256-bit AES key using PBKDF2.
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val derived = factory.generateSecret(spec).encoded
        return SecretKeySpec(derived, "AES")
    }
}
