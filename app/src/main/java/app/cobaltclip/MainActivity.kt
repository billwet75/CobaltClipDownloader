package app.cobaltclip

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.cobaltclip.data.DownloadRecord
import app.cobaltclip.data.UserSettings
import app.cobaltclip.download.DownloadService
import app.cobaltclip.download.UrlMatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND) {
            val text = sharedText(intent)
            lifecycleScope.launch {
                val settings = (application as CobaltApp).settings.flow.first()
                UrlMatcher.extractAll(text).forEach {
                    enqueueDownload(
                        this@MainActivity,
                        it,
                        settings.downloadMode,
                        settings.quality,
                        settings.incognito
                    )
                }
                finishAndRemoveTask()
            }
            return
        }
        setContent { MaterialTheme { AppScreen(viewModel, sharedText(intent)) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent)?.let { text ->
            lifecycleScope.launch {
                val settings = (application as CobaltApp).settings.flow.first()
                UrlMatcher.extractAll(text).forEach {
                    enqueueDownload(
                        this@MainActivity,
                        it,
                        settings.downloadMode,
                        settings.quality,
                        settings.incognito
                    )
                }
                finishAndRemoveTask()
            }
        }
    }

    private fun sharedText(intent: Intent): String? =
        if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(vm: MainViewModel, initialSharedText: String?) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    val textFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()?.let { imported ->
            input = listOf(input, imported).filter(String::isNotBlank).joinToString("\n")
        }
    }
    val outputFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        vm.setOutputTreeUri(uri.toString())
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        initialSharedText?.let { text ->
            UrlMatcher.extract(text)?.let {
                input = it
                if (settings.autoDownload) {
                    enqueueDownload(
                        context,
                        it,
                        settings.downloadMode,
                        settings.quality,
                        settings.incognito
                    )
                }
            }
        }
    }

    ClipboardWatcher(settings.autoDownload) { url ->
        input = url
        if (settings.autoDownload) {
            enqueueDownload(
                context,
                url,
                settings.downloadMode,
                settings.quality,
                settings.incognito
            )
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(tab) {
                listOf("Загрузка", "История", "Настройки").forEachIndexed { index, title ->
                    Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> DownloadTab(
                    input = input,
                    onInput = { input = it },
                    settings = settings,
                    onImport = { textFilePicker.launch("text/plain") }
                ) { delayMinutes ->
                    val scheduledAt = if (delayMinutes > 0) {
                        System.currentTimeMillis() + delayMinutes * 60_000L
                    } else {
                        0
                    }
                    UrlMatcher.extractAll(input).forEach {
                        enqueueDownload(
                            context,
                            it,
                            settings.downloadMode,
                            settings.quality,
                            settings.incognito,
                            scheduledAt
                        )
                    }
                }
                1 -> HistoryTab(history, vm)
                else -> SettingsTab(
                    settings,
                    vm,
                    onChooseFolder = { outputFolderPicker.launch(null) }
                )
            }
        }
    }
}

@Composable
private fun ClipboardWatcher(auto: Boolean, onUrl: (String) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(auto) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var last: String? = null
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            UrlMatcher.extract(text)?.takeIf { it != last }?.let {
                last = it
                onUrl(it)
            }
        }
        clipboard.addPrimaryClipChangedListener(listener)
        onDispose { clipboard.removePrimaryClipChangedListener(listener) }
    }
}

@Composable
private fun DownloadTab(
    input: String,
    onInput: (String) -> Unit,
    settings: UserSettings,
    onImport: () -> Unit,
    onDownload: (Long) -> Unit
) {
    var delayMinutes by remember { mutableStateOf("0") }
    val urls = UrlMatcher.extractAll(input)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Ссылки на поддерживаемые сервисы", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Одна или несколько ссылок") },
            supportingText = { Text("Найдено ссылок: ${urls.size}") },
            minLines = 3
        )
        TextButton(onClick = onImport) { Text("Импортировать ссылки из TXT") }
        OutlinedTextField(
            value = delayMinutes,
            onValueChange = { delayMinutes = it.filter(Char::isDigit).take(5) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Запустить через, минут") },
            supportingText = { Text("0 — начать сейчас") }
        )
        Text(
            "Профиль: ${if (settings.downloadMode == "audio") "аудио" else "видео"}, " +
                settings.quality
        )
        Button(
            onClick = { onDownload(delayMinutes.toLongOrNull() ?: 0) },
            enabled = urls.isNotEmpty()
        ) {
            Text(if (urls.size > 1) "Добавить ${urls.size} в очередь" else "Добавить в очередь")
        }
        Text(
            "На Android 10+ системная защита разрешает чтение буфера только пока приложение на экране. " +
                "Для надежной работы выберите «Поделиться» → Cobalt Clip в YouTube, VK или Instagram.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Скачивайте только контент, который принадлежит вам или на сохранение которого у вас есть разрешение.",
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HistoryTab(history: List<DownloadRecord>, vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Загрузки", style = MaterialTheme.typography.titleLarge)
            TextButton(
                onClick = vm::clearHistory,
                enabled = history.any { it.status in listOf("COMPLETED", "FAILED", "CANCELLED") }
            ) { Text("Очистить готовые") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.filename.ifBlank { item.sourceUrl }, style = MaterialTheme.typography.titleMedium)
                        Text(statusLabel(item.status, item.progress))
                        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(
                            "${if (item.downloadMode == "audio") "Аудио" else "Видео"} · " +
                                item.quality,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (item.scheduledAt > System.currentTimeMillis() && item.status == "QUEUED") {
                            Text(
                                "Запланировано: ${java.text.DateFormat.getDateTimeInstance().format(item.scheduledAt)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(item.sourceUrl, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.status == "FAILED" || item.status == "CANCELLED") {
                                TextButton(onClick = { vm.retry(item.id) }) { Text("Повторить") }
                            }
                            if (item.status in listOf("QUEUED", "RESOLVING", "DOWNLOADING")) {
                                TextButton(onClick = { vm.cancel(item.id) }) { Text("Отменить") }
                            }
                            if (item.status == "COMPLETED" && item.mediaUri != null) {
                                TextButton(onClick = {
                                    shareMedia(context, Uri.parse(item.mediaUri))
                                }) { Text("Поделиться") }
                            }
                            if (item.error != null) {
                                TextButton(onClick = {
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Cobalt Clip error",
                                            "${item.error}\n${item.sourceUrl}"
                                        )
                                    )
                                }) { Text("Копировать ошибку") }
                            }
                            if (item.status in listOf("COMPLETED", "FAILED", "CANCELLED")) {
                                TextButton(onClick = { vm.delete(item.id) }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: UserSettings,
    vm: MainViewModel,
    onChooseFolder: () -> Unit
) {
    var endpoint by remember(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var vkToken by remember(settings.vkToken) { mutableStateOf(settings.vkToken) }
    val qualities = listOf("360", "480", "720", "1080", "1440", "2160", "max")
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Режим загрузки")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setDownloadMode("auto") }) {
                Text(if (settings.downloadMode == "auto") "[Видео]" else "Видео")
            }
            TextButton(onClick = { vm.setDownloadMode("audio") }) {
                Text(if (settings.downloadMode == "audio") "[Аудио]" else "Аудио")
            }
        }
        Text("Быстрые профили")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.applyProfile("auto", "720") }) { Text("Экономный") }
            TextButton(onClick = { vm.applyProfile("auto", "1080") }) { Text("1080p") }
            TextButton(onClick = { vm.applyProfile("auto", "max") }) { Text("Максимум") }
            TextButton(onClick = { vm.applyProfile("audio", "max") }) { Text("Аудио") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.fillMaxWidth(0.78f)) {
                Text("Автозагрузка")
                Text("Когда ссылка обнаружена при открытом приложении или передана через «Поделиться»",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.autoDownload, vm::setAuto)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.fillMaxWidth(0.78f)) {
                Text("Инкогнито")
                Text(
                    "Не сохранять задачи и ошибки в истории после завершения",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(settings.incognito, vm::setIncognito)
        }
        Text("Папка сохранения")
        Text(
            if (settings.outputTreeUri.isBlank()) {
                "Стандартные папки Music/Pictures/Movies/CobaltClip"
            } else {
                settings.outputTreeUri
            },
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChooseFolder) { Text("Выбрать папку") }
            if (settings.outputTreeUri.isNotBlank()) {
                TextButton(onClick = { vm.setOutputTreeUri("") }) {
                    Text("Вернуть стандартную")
                }
            }
        }
        Text("Качество видео")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            qualities.chunked(4).first().forEach { q ->
                TextButton(onClick = { vm.setQuality(q) }) {
                    Text(if (q == settings.quality) "[$q]" else q)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            qualities.chunked(4).getOrElse(1) { emptyList() }.forEach { q ->
                TextButton(onClick = { vm.setQuality(q) }) {
                    Text(if (q == settings.quality) "[$q]" else q)
                }
            }
        }
        OutlinedTextField(
            endpoint, { endpoint = it },
            Modifier.fillMaxWidth(), label = { Text("Адрес cobalt API") },
            supportingText = { Text("Например, https://cobalt.example.com") }
        )
        OutlinedTextField(
            apiKey, { apiKey = it },
            Modifier.fillMaxWidth(), label = { Text("API key (необязательно)") },
            visualTransformation = PasswordVisualTransformation()
        )
        Text("ВКонтакте", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            vkToken, { vkToken = it },
            Modifier.fillMaxWidth(), label = { Text("VK access token (для приватных видео)") },
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Оставьте пустым для публичных видео через cobalt") }
        )
        Button(onClick = { vm.setEndpoint(endpoint); vm.setApiKey(apiKey); vm.setVkToken(vkToken) }) {
            Text("Сохранить настройки сервера")
        }
        Text(
            "Публичный api.cobalt.tools не предназначен для сторонних приложений без разрешения. " +
                "Разверните собственный экземпляр cobalt на VPS и подключите его через HTTPS. " +
                "Инструкция: github.com/imputnet/cobalt/blob/main/docs/run-an-instance.md",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun statusLabel(status: String, progress: Int) = when (status) {
    "QUEUED" -> "В очереди"
    "RESOLVING" -> "Получение ссылки…"
    "DOWNLOADING" -> "Загрузка: $progress%"
    "COMPLETED" -> "Готово"
    "FAILED" -> "Ошибка"
    "CANCELLED" -> "Отменено"
    else -> status
}

private fun enqueueDownload(
    context: Context,
    url: String,
    mode: String,
    quality: String,
    incognito: Boolean = false,
    scheduledAt: Long = 0
) = DownloadService.enqueue(context, url, mode, quality, incognito, scheduledAt)

private fun shareMedia(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(context.contentResolver.getType(uri) ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
}
