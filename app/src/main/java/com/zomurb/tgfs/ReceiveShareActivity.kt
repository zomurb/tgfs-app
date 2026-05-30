package com.zomurb.tgfs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zomurb.tgfs.data.worker.UploadWorker
import com.zomurb.tgfs.ui.theme.MyApplicationTheme

/**
 * Activity capturing system share intents to upload arbitrary files
 * into the TGFS unlimited cloud system directly.
 */
class ReceiveShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var sharedUri: Uri? = null

        // Capture files from action SEND
        if (intent != null && intent.action == Intent.ACTION_SEND) {
            val type = intent.type
            if (type != null) {
                sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        }

        if (sharedUri == null) {
            Toast.makeText(this, "No valid content shared.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Read file metadata
        val (fileName, fileSize) = getFileMetadata(this, sharedUri)

        setContent {
            MyApplicationTheme {
                Scaffold { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        ShareImportCard(
                            fileName = fileName,
                            fileSize = fileSize,
                            onUploadConfirm = { password ->
                                triggerBackgroundUpload(sharedUri, password)
                            },
                            onCancel = {
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun triggerBackgroundUpload(uri: Uri, password: String?) {
        val passwordId = if (!password.isNullOrBlank()) {
            UploadWorker.cachePassword(password)
        } else {
            null
        }

        val data = workDataOf(
            UploadWorker.KEY_FILE_URI to uri.toString(),
            UploadWorker.KEY_PASSWORD_ID to passwordId
        )

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(uploadRequest)
        Toast.makeText(this, "TGFS background file transfer initiated!", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun getFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_shared_file"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("ReceiveShare", "Failed reading shared file metadata", e)
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment ?: "shared_file_${System.currentTimeMillis()}"
        }
        return Pair(name, size)
    }
}

@Composable
fun ShareImportCard(
    fileName: String,
    fileSize: Long,
    onUploadConfirm: (String?) -> Unit,
    onCancel: () -> Unit
) {
    var isEncryptionEnabled by remember { mutableStateOf(true) }
    var passwordInput by remember { mutableStateOf("") }
    val formattedSize = remember(fileSize) {
        val mb = fileSize.toDouble() / (1024.0 * 1024.0)
        String.format("%.2f MB", mb)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Incoming Share",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Import to TGFS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // File Information Details
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Size: $formattedSize",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Chunk Encryption Toggle Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "AES-GCM Secure Chunks",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Switch(
                            checked = isEncryptionEnabled,
                            onCheckedChange = { isEncryptionEnabled = it },
                            modifier = Modifier.testTag("encryption_switch")
                        )
                    }

                    if (isEncryptionEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Master Password") },
                            placeholder = { Text("Enter encryption key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_input"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cancel_share_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        val finalPass = if (isEncryptionEnabled) passwordInput else null
                        onUploadConfirm(finalPass)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("upload_share_button"),
                    enabled = !isEncryptionEnabled || passwordInput.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Split & Sync")
                }
            }
        }
    }
}
