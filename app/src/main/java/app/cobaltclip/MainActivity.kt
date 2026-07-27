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
                if (settings.autoDownload) {
                    UrlMatcher.extract(text)?.let { enqueueDownload(this@MainActivity, it) }
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
                if ((application as CobaltApp).settings.flow.first().autoDownload) {
                    enqueueIfSupported(text)
                }
                finishAndRemoveTask()
            }
        }
    }

    private fun sharedText(intent: Intent): String? =
        if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null

    private fun enqueueIfSupported(text: String) {
        UrlMatcher.extract(text)?.let { enqueueDownload(this, it) }
    }
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

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        initialSharedText?.let { text ->
            UrlMatcher.extract(text)?.let {
                input = it
                if (settings.autoDownload) enqueueDownload(context, it)
            }
        }
    }

    ClipboardWatcher(settings.autoDownload) { url ->
        input = url
        if (settings.autoDownload) enqueueDownload(context, url)
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(tab) {
                listOf("Загрузка", "История", "Настройки").forEachIndexed { index, title ->
                    Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> DownloadTab(input, { input = it }) {
                    UrlMatcher.extract(input)?.let { enqueueDownload(context, it) }
                }
                1 -> HistoryTab(history, vm::clearHistory)
                else -> SettingsTab(settings, vm)
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
private fun DownloadTab(input: String, onInput: (String) -> Unit, onDownload: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ссылка на YouTube или Instagram", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Вставьте ссылку") },
            minLines = 2
        )
        Button(onClick = onDownload, enabled = UrlMatcher.extract(input) != null) {
            Text("Скачать в галерею")
        }
        Text(
            "На Android 10+ системная защита разрешает чтение буфера только пока приложение на экране. " +
                "Для надежной работы выберите «Поделиться» → Cobalt Clip в YouTube или Instagram.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Скачивайте только контент, который принадлежит вам или на сохранение которого у вас есть разрешение.",
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HistoryTab(history: List<DownloadRecord>, clear: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Загрузки", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = clear, enabled = history.isNotEmpty()) { Text("Очистить") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.filename.ifBlank { item.sourceUrl }, style = MaterialTheme.typography.titleMedium)
                        Text(statusLabel(item.status, item.progress))
                        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(item.sourceUrl, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.status == "FAILED" || item.status == "CANCELLED") {
                                TextButton(onClick = {
                                    enqueueDownload(context, item.sourceUrl)
                                }) { Text("Повторить") }
                            }
                            if (item.status == "COMPLETED" && item.mediaUri != null) {
                                TextButton(onClick = {
                                    shareMedia(context, Uri.parse(item.mediaUri))
                                }) { Text("Поделиться") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(settings: UserSettings, vm: MainViewModel) {
    var endpoint by remember(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    val qualities = listOf("360", "480", "720", "1080", "1440", "2160", "max")
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Режим загрузки")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setDownloadMode("auto") }) {
                Text(if (settings.downloadMode == "auto") "[Видео]" else "Видео")
            }
            TextButton(onClick = { vm.setDownloadMode("audio") }) {
                Text(if (settings.downloadMode == "audio") "[Аудио]" else "Аудио")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.fillMaxWidth(0.78f)) {
                Text("Автозагрузка")
                Text("Когда ссылка обнаружена при открытом приложении или передана через «Поделиться»",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.autoDownload, vm::setAuto)
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
        Button(onClick = { vm.setEndpoint(endpoint); vm.setApiKey(apiKey) }) {
            Text("Сохранить настройки сервера")
        }
        Text(
            "Публичный api.cobalt.tools не предназначен для сторонних приложений без разрешения. " +
                "Используйте собственный экземпляр или сервер, владелец которого дал доступ.",
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

private fun enqueueDownload(context: Context, url: String) {
    val intent = Intent(context, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, url)
    ContextCompat.startForegroundService(context, intent)
}

private fun shareMedia(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(context.contentResolver.getType(uri) ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
}
