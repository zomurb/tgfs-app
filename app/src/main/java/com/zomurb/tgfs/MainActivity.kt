package com.zomurb.tgfs

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zomurb.tgfs.domain.model.TgfChunk
import com.zomurb.tgfs.domain.model.TgfFile
import com.zomurb.tgfs.domain.repository.TelegramClient
import com.zomurb.tgfs.presentation.TgfViewModel
import com.zomurb.tgfs.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    TgfDashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TgfDashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: TgfViewModel = viewModel()
) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsState()
    val filesList by viewModel.filesState.collectAsState()

    var showUploadDialog by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Navigation drawer states
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showAboutDialog by remember { mutableStateOf(false) }

    // Dialogue for downloading/decrypting
    var fileToDownload by remember { mutableStateOf<TgfFile?>(null) }
    var decryptPassword by remember { mutableStateOf("") }
    var showDownloadDialog by remember { mutableStateOf(false) }

    // Overlay progress dialog during download process
    var downloadStageText by remember { mutableStateOf<String?>(null) }

    // Storage access framework launcher to pick files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            showUploadDialog = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo_vector),
                        contentDescription = "TGFS Logo",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "TGFS",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Зашифрованное облако в Telegram",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp))

                NavigationDrawerItem(
                    label = { Text("Хранилище файлов", style = MaterialTheme.typography.labelLarge) },
                    selected = selectedTabIndex == 0,
                    onClick = {
                        selectedTabIndex = 0
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Хранилище") },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    label = { Text("Параметры и бэкап", style = MaterialTheme.typography.labelLarge) },
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Параметры") },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(modifier = Modifier.weight(1f))

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Автор: Зоитов Мухаммад",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            showAboutDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "О проекте",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "О проекте и контакты",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectedTabIndex == 0) "TGFS • Хранилище" else "TGFS • Настройки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Меню")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAboutDialog = true }) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = "О проекте")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            },
            floatingActionButton = {
                if (selectedTabIndex == 0) {
                    FloatingActionButton(
                        onClick = {
                            val prefs = context.getSharedPreferences("tgfs_prefs", Context.MODE_PRIVATE)
                            val botToken = prefs.getString("bot_token", "") ?: ""
                            val chatId = prefs.getString("chat_id", "") ?: ""
                            if (botToken.isBlank() || chatId.isBlank()) {
                                Toast.makeText(context, "Пожалуйста, сначала настройте подключение к вашему Telegram-боту во вкладке Настройки!", Toast.LENGTH_LONG).show()
                                selectedTabIndex = 1
                            } else {
                                filePickerLauncher.launch("*/*")
                            }
                        },
                        modifier = Modifier.testTag("fab_upload"),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Загрузить файл"
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
            when (selectedTabIndex) {
                0 -> {
                    // Storage Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Brand Header (Visible only in Storage tab, below tabs menu)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo_vector),
                                contentDescription = "TGFS Logo",
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "TGFS",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Безлимитное хранилище файлов в Telegram",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        // Quick status banner
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Информация о статусе",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Приложение готово к разделению и зашифрованной загрузке ваших файлов напрямую в ваш личный чат Telegram.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        // Files list
                        Text(
                            text = "Индексированные файлы в хранилище (${filesList.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (filesList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(16.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Пусто",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(54.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Ваш локальный индекс хранилища пуст.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Нажмите кнопку [+] ниже, чтобы загрузить ваш первый разделенный файл.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filesList, key = { it.id }) { file ->
                                    FileRecordItem(
                                        file = file,
                                        onDeleteClick = { viewModel.deleteFileRecord(file.id) },
                                        onDownloadClick = {
                                            fileToDownload = file
                                            if (file.isEncrypted) {
                                                val savedPw = context.getSharedPreferences("tgfs_prefs", Context.MODE_PRIVATE)
                                                    .getString("master_password", "") ?: ""
                                                decryptPassword = savedPw
                                                showDownloadDialog = true
                                            } else {
                                                // Trigger plain assembly directly
                                                viewModel.downloadAndAssembleFile(
                                                    file = file,
                                                    password = null,
                                                    context = context,
                                                    onProgress = { downloadStageText = it },
                                                    onCompleted = { msg ->
                                                        downloadStageText = null
                                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                    },
                                                    onFailed = { err ->
                                                        downloadStageText = null
                                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        },
                                        onFileExpand = { viewModel.getChunksForFile(file.id) }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Settings Connection panel
                    ConnectionSettingsView(context = context, viewModel = viewModel)
                }
            }
        }
    }

    // Modal dialog setting encryption options before splitting & uploading chunks
    if (showUploadDialog) {
        val prefs = remember { context.getSharedPreferences("tgfs_prefs", Context.MODE_PRIVATE) }
        val savedMasterPassword = remember { prefs.getString("master_password", "") ?: "" }
        var isEncryptionEnabled by remember { mutableStateOf(savedMasterPassword.isNotEmpty()) }
        var masterPass by remember { mutableStateOf(savedMasterPassword) }

        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("Разделение и подготовка файла") },
            text = {
                Column {
                    Text(
                        text = "Ваши файлы разделяются на равные блоки по 48 МБ перед отправкой. Активируйте локальное шифрование AES-GCM для максимальной приватности.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = "Замок", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Шифрование AES-256-GCM")
                        }
                        Switch(
                            checked = isEncryptionEnabled,
                            onCheckedChange = { isEncryptionEnabled = it },
                            modifier = Modifier.testTag("encryption_modal_switch")
                        )
                    }

                    if (isEncryptionEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = masterPass,
                            onValueChange = { masterPass = it },
                            label = { Text("Мастер-пароль расшифрования") },
                            placeholder = { Text("Используется для генерации соли и ключа PBKDF2") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("modal_password_input"),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedFileUri?.let { uri ->
                            viewModel.startUpload(uri, isEncryptionEnabled, masterPass)
                            Toast.makeText(context, "Синхронизация и загрузка в облако успешно начаты!", Toast.LENGTH_SHORT).show()
                        }
                        showUploadDialog = false
                    },
                    modifier = Modifier.testTag("confirm_upload_button"),
                    enabled = !isEncryptionEnabled || masterPass.isNotBlank()
                ) {
                    Text("Разделить и отправить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUploadDialog = false },
                    modifier = Modifier.testTag("dismiss_upload_button")
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    // Password input modal dialogue for encrypted files downloads
    if (showDownloadDialog && fileToDownload != null) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Разблокировать и расшифровать файл") },
            text = {
                Column {
                    Text(
                        text = "Введите мастер-пароль шифрования для вычисления правильных криптографических хэшей и сборки исходной последовательности байт.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = decryptPassword,
                        onValueChange = { decryptPassword = it },
                        label = { Text("Мастер-пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        fileToDownload?.let { file ->
                            viewModel.downloadAndAssembleFile(
                                file = file,
                                password = decryptPassword,
                                context = context,
                                onProgress = { downloadStageText = it },
                                onCompleted = { msg ->
                                    downloadStageText = null
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                },
                                onFailed = { err ->
                                    downloadStageText = null
                                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        showDownloadDialog = false
                    },
                    enabled = decryptPassword.isNotBlank()
                ) {
                    Text("Расшифровать и собрать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Dynamic linear progress popup covering reconstruction sequences
    if (downloadStageText != null) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss during active file composition */ },
            title = { Text("Сборка потока данных") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = downloadStageText ?: "Обработка и восстановление...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "TGFS последовательно объединяет сегменты байт в оперативной памяти для воссоздания оригинального файла.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "О проекте",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("О проекте TGFS")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "TGFS (Telegram File Storage) — это современный инструмент безопасного бесшовного шифрования и распределенного облачного хранения данных.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Преимущества решения:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• Zero-Knowledge шифрование AES-256-GCM\n" +
                               "• Автоматическая нарезка файлов на сегменты\n" +
                               "• Облачное дублирование в защищенный Канал\n" +
                               "• Никаких ограничений на общий объем и скорость",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Разработчик и автор:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Зоитов Мухммад",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Email: kevinmitnikov@gmail.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Буду рад вашей обратной связи, предложениям и сообщениям об ошибках!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://t.me/zomurb"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ссылка: t.me/zomurb", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Telegram")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Написать в Telegram")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("email", "kevinmitnikov@gmail.com")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "E-mail скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "kevinmitnikov@gmail.com", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Email, contentDescription = "Email")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Скопировать Email")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}
}

@Composable
fun FileRecordItem(
    file: TgfFile,
    onDeleteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onFileExpand: (Long) -> kotlinx.coroutines.flow.Flow<List<TgfChunk>>
) {
    var isExpanded by remember { mutableStateOf(false) }
    val formattedSize = remember(file.size) {
        val mb = file.size.toDouble() / (1024.0 * 1024.0)
        String.format("%.2f MB", mb)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .testTag("file_item_${file.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Info block
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (file.isEncrypted) Icons.Default.Lock else Icons.Default.List,
                        contentDescription = "Тип",
                        tint = if (file.isEncrypted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Частей: ${file.totalChunks} | Размер: $formattedSize",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Download & Assemble Index button
                    IconButton(
                        onClick = onDownloadClick,
                        modifier = Modifier.testTag("download_file_${file.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Скачать и собрать",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Delete index
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.testTag("delete_file_${file.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить индекс",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                var chunkListState by remember { mutableStateOf<List<TgfChunk>>(emptyList()) }
                LaunchedEffect(file.id) {
                    onFileExpand(file.id).collect {
                        chunkListState = it
                    }
                }

                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Индексированные веб-части в Telegram:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    if (chunkListState.isEmpty()) {
                        Text(
                            text = "Загрузка заголовков частей...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        chunkListState.forEach { chunk ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "  Индекс части: #${chunk.partIndex + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Text(
                                    text = "ID сообщения: tg://${chunk.messageId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }


                }
            }
        }
    }
}

@Composable
fun ConnectionSettingsView(context: Context, viewModel: TgfViewModel) {
    val prefs = remember { context.getSharedPreferences("tgfs_prefs", Context.MODE_PRIVATE) }
    var botToken by remember { mutableStateOf(prefs.getString("bot_token", "") ?: "") }
    var chatId by remember { mutableStateOf(prefs.getString("chat_id", "") ?: "") }
    var masterPassword by remember { mutableStateOf(prefs.getString("master_password", "") ?: "") }

    var recoveryStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Параметры подключения",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Настройте Telegram-бот и канал для надежного и безопасного облачного хранения сегментов файлов. TGFS локально обрабатывает данные и шифрует их на вашей стороне.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = botToken,
            onValueChange = { botToken = it },
            label = { Text("Токен Telegram-бота") },
            placeholder = { Text("0123456789:abcdEFgHiJKl...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = chatId,
            onValueChange = { chatId = it },
            label = { Text("ID или @username канала в Telegram") },
            placeholder = { Text("-100XXXXXXXXXX или @my_tgfs_channel") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = masterPassword,
            onValueChange = { masterPassword = it },
            label = { Text("Мастер-пароль по умолчанию (Необязательно)") },
            placeholder = { Text("Избавит от необходимости вводить его при каждой операции") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                prefs.edit()
                    .putString("bot_token", botToken.trim())
                    .putString("chat_id", chatId.trim())
                    .putString("master_password", masterPassword)
                    .apply()
                Toast.makeText(context, "Параметры подключения и учетные данные успешно сохранены!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_config_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Check, contentDescription = "Сохранить")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сохранить конфигурацию")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Recovery trigger section
        Text(
            text = "Облачное восстановление",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Используйте Zero-Knowledge восстановление, чтобы загрузить последнюю зашифрованную резервную копию базы данных прямо из вашего чата с ботом Telegram и восстановить все индексы файлов хранилища.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Button(
                    onClick = {
                        val pass = masterPassword
                        if (pass.isBlank()) {
                            Toast.makeText(context, "Пожалуйста, введите ваш мастер-пароль шифрования выше для дешифрования бэкапа!", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.triggerCloudRecovery(
                                context = context,
                                masterPassword = pass,
                                onStatus = { recoveryStatus = it },
                                onResult = { success, msg ->
                                    recoveryStatus = null
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Синхронизировать")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Восстановить индексы из облака")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Инструкция по настройке:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. Создайте новый публичный или приватный Канал в Telegram для хранения зашифрованных копий. В случае утери аккаунта, доступ к каналу всегда можно восстановить.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    text = "2. Найдите в Telegram бота @BotFather, создайте своего бота командой /newbot и скопируйте его токен во второе поле выше.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    text = "3. Добавьте созданного бота в ваш Канал в роли Администратора с правом публикации сообщений (Post Messages).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    text = "4. Укажите @username канала (например, @my_tgfs_channel) или его числовой ID (начинается на -100) во втором поле выше.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }

    // Modal dialog displaying cloud recovery progress
    if (recoveryStatus != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Восстановление индексов") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = recoveryStatus ?: "Получение данных...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {}
        )
    }
}
