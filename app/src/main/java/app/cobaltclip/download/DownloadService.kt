package app.cobaltclip.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.documentfile.provider.DocumentFile
import app.cobaltclip.CobaltApp
import app.cobaltclip.MainActivity
import app.cobaltclip.data.DownloadRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.TimeUnit

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = CobaltClient()
    private var activeJob: Job? = null
    private var currentId: Long? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getLongExtra(EXTRA_ID, -1)
            if (activeJob == null) startInForeground("Отмена задачи…", 0)
            scope.launch {
                (application as CobaltApp).database.downloads().cancel(id)
                if (activeJob == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            if (currentId == id) {
                activeJob?.cancel()
            }
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_URL)
        if (source != null) {
            if (activeJob == null) startInForeground("Добавление в очередь…", 0)
            scope.launch {
                val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0)
                val record = DownloadRecord(
                    sourceUrl = source,
                    downloadMode = intent.getStringExtra(EXTRA_MODE) ?: "auto",
                    quality = intent.getStringExtra(EXTRA_QUALITY) ?: "1080",
                    incognito = intent.getBooleanExtra(EXTRA_INCOGNITO, false),
                    scheduledAt = scheduledAt
                )
                (application as CobaltApp).database.downloads().insert(record)
                if (scheduledAt > System.currentTimeMillis()) {
                    scheduleWakeUp(scheduledAt)
                    if (activeJob == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    startQueue()
                }
            }
            return START_NOT_STICKY
        }
        startQueue()
        return START_NOT_STICKY
    }

    private fun startQueue() {
        if (activeJob == null) {
            startInForeground("Подготовка загрузки…", 0)
            activeJob = scope.launch { processQueue() }
        }
    }

    private suspend fun processQueue() {
        var allCompleted = true
        try {
            val dao = (application as CobaltApp).database.downloads()
            dao.recoverInterrupted()
            while (true) {
                val record = dao.nextReady() ?: break
                currentId = record.id
                allCompleted = download(record) && allCompleted
            }
        } finally {
            currentId = null
            activeJob = null
            val readyRemains = (application as CobaltApp).database.downloads().nextReady() != null
            if (!allCompleted) {
                updateNotification("Очередь завершена с ошибками", 0, complete = true)
            }
            stopForeground(if (allCompleted) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            stopSelf()
            if (readyRemains) {
                process(applicationContext)
            }
        }
    }

    private suspend fun download(record: DownloadRecord): Boolean {
        val app = application as CobaltApp
        val dao = app.database.downloads()
        val recordId = record.id
        var completed = false
        try {
            val settings = app.settings.flow.first().copy(
                downloadMode = record.downloadMode,
                quality = record.quality
            )
            dao.update(recordId, "RESOLVING", 0)
            val files = client.resolve(record.sourceUrl, settings)
            files.forEachIndexed { index, remote ->
                val label = if (files.size > 1) "${index + 1}/${files.size}: ${remote.filename}" else remote.filename
                dao.update(recordId, "DOWNLOADING", 0, label)
                saveRemote(remote, settings.outputTreeUri) { progress, details ->
                    scope.launch {
                        dao.update(recordId, "DOWNLOADING", progress, label)
                        updateNotification("Загрузка $label · $details", progress)
                    }
                }.also { uri ->
                    dao.update(recordId, "COMPLETED", 100, label, mediaUri = uri.toString())
                }
            }
            completed = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                dao.update(recordId, "CANCELLED", 0, error = "Отменено")
            }
        } catch (error: Exception) {
            dao.update(recordId, "FAILED", 0, error = error.message ?: "Неизвестная ошибка")
            updateNotification(error.message ?: "Ошибка загрузки", 0, complete = true)
        } finally {
            val remaining = dao.pendingCount()
            if (remaining > 0) {
                updateNotification("Следующая загрузка… Осталось: $remaining", 0)
            }
        }
        if (record.incognito) {
            withContext(NonCancellable) { dao.delete(recordId) }
        }
        return completed
    }

    private suspend fun saveRemote(
        remote: RemoteFile,
        outputTreeUri: String,
        progress: (Int, String) -> Unit
    ): Uri =
        withContext(Dispatchers.IO) {
            val partFile = File.createTempFile("cobalt_", ".part", cacheDir)
            var downloadedContentType: String? = null
            try {
                client.retryTransient {
                    val offset = partFile.length()
                    client.execute(client.newDownloadRequest(remote.url, offset)).use { response ->
                        if (response.code == 416 && offset > 0) {
                            partFile.delete()
                            throw TransientHttpException("Сервер отклонил продолжение загрузки")
                        }
                        if (response.code == 429 || response.code in 500..599) {
                            throw TransientHttpException(
                                "Временная ошибка файла HTTP ${response.code}",
                                response.header("Retry-After")?.toLongOrNull()
                            )
                        }
                        if (!response.isSuccessful) {
                            throw CobaltException("Ошибка файла HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Пустой ответ при загрузке")
                        downloadedContentType = body.contentType()?.toString()?.substringBefore(';')
                        val resumed = offset > 0 && response.code == 206
                        val completedBeforeRequest = if (resumed) offset else 0L
                        val expectedTotal = if (body.contentLength() > 0) {
                            completedBeforeRequest + body.contentLength()
                        } else {
                            -1L
                        }
                        FileOutputStream(partFile, resumed).use { output ->
                            body.byteStream().use { input ->
                                copyWithProgress(
                                    input,
                                    output,
                                    expectedTotal,
                                    completedBeforeRequest,
                                    progress
                                )
                            }
                        }
                    }
                }

                val contentType = downloadedContentType
                    ?: URLConnection.guessContentTypeFromName(remote.filename)
                    ?: when (remote.type) {
                        "photo" -> "image/jpeg"
                        "gif" -> "image/gif"
                        else -> "video/mp4"
                    }
                val filename = safeFilename(
                    remote.filename.ifBlank { "cobalt_${System.currentTimeMillis()}.${extension(contentType)}" }
                )
                if (outputTreeUri.isNotBlank()) {
                    val treeUri = Uri.parse(outputTreeUri)
                    val directory = DocumentFile.fromTreeUri(this@DownloadService, treeUri)
                        ?: throw CobaltException("Выбранная папка больше недоступна")
                    val target = directory.createFile(contentType, filename)
                        ?: throw CobaltException("Не удалось создать файл в выбранной папке")
                    try {
                        contentResolver.openOutputStream(target.uri)?.use { output ->
                            partFile.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw CobaltException("Не удалось открыть выбранную папку для записи")
                        return@withContext target.uri
                    } catch (error: Exception) {
                        target.delete()
                        throw error
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val collection = when {
                        contentType.startsWith("image/") ->
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        contentType.startsWith("audio/") ->
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, contentType)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            when {
                                contentType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                                contentType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                                else -> Environment.DIRECTORY_MOVIES
                            } + "/CobaltClip"
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(collection, values)
                        ?: throw CobaltException("Не удалось создать файл в галерее")
                    try {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            partFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        } ?: throw CobaltException("Не удалось открыть файл для записи")
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        uri
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                } else throw CobaltException("Требуется Android 10 или новее")
            } finally {
                partFile.delete()
            }
        }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        initial: Long = 0,
        onProgress: (Int, String) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = initial
        var last = -1
        var lastUpdate = 0L
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 500 || copied == total) {
                val value = if (total > 0) {
                    ((copied * 100) / total).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                if (value != last || now - lastUpdate >= 1_000) {
                    last = value
                    lastUpdate = now
                    val elapsedSeconds = ((now - startedAt) / 1_000.0).coerceAtLeast(0.1)
                    val downloadedThisRequest = (copied - initial).coerceAtLeast(0)
                    val bytesPerSecond = (downloadedThisRequest / elapsedSeconds).toLong()
                    val eta = if (total > 0 && bytesPerSecond > 0) {
                        " · ${formatDuration((total - copied) / bytesPerSecond)}"
                    } else {
                        ""
                    }
                    onProgress(value, "${formatBytes(copied)} · ${formatBytes(bytesPerSecond)}/с$eta")
                }
            }
        }
    }

    private fun startInForeground(text: String, progress: Int) {
        val notification = notification(text, progress, false)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun updateNotification(text: String, progress: Int, complete: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, progress, complete))
    }

    private fun notification(text: String, progress: Int, complete: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (complete) "Cobalt Clip" else "Cobalt Clip загружает")
            .setContentText(text)
            .setOnlyAlertOnce(!complete)
            .setOngoing(!complete)
            .setAutoCancel(complete)
            .setProgress(100, progress, progress == 0 && !complete)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .apply {
                if (!complete) addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Отмена",
                    PendingIntent.getService(
                        this@DownloadService, 1,
                        Intent(this@DownloadService, DownloadService::class.java)
                            .setAction(ACTION_CANCEL)
                            .putExtra(EXTRA_ID, currentId ?: -1),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }.build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Загрузки", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun scheduleWakeUp(scheduledAt: Long) {
        val delay = (scheduledAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ScheduledDownloadWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private fun safeFilename(value: String) =
        value.replace(Regex("""[\\/:*?"<>|]"""), "_").take(180).ifBlank { "media" }
    private fun extension(mime: String) = when {
        mime.contains("jpeg") -> "jpg"
        mime.contains("png") -> "png"
        mime.contains("gif") -> "gif"
        mime.contains("mpeg") -> "mp3"
        mime.contains("mp4a") || mime.contains("m4a") -> "m4a"
        mime.contains("flac") -> "flac"
        mime.contains("ogg") -> "ogg"
        mime.contains("webm") -> "webm"
        else -> "mp4"
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1_073_741_824 -> "%.1f ГБ".format(value / 1_073_741_824.0)
        value >= 1_048_576 -> "%.1f МБ".format(value / 1_048_576.0)
        value >= 1_024 -> "%.1f КБ".format(value / 1_024.0)
        else -> "$value Б"
    }

    private fun formatDuration(seconds: Long): String =
        if (seconds >= 60) "${seconds / 60} мин ${seconds % 60} с"
        else "$seconds с"

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_INCOGNITO = "incognito"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val ACTION_CANCEL = "cancel"
        private const val ACTION_PROCESS = "process"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42

        fun enqueue(
            context: android.content.Context,
            url: String,
            mode: String,
            quality: String,
            incognito: Boolean = false,
            scheduledAt: Long = 0
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_QUALITY, quality)
                .putExtra(EXTRA_INCOGNITO, incognito)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
            ContextCompat.startForegroundService(context, intent)
        }

        fun process(context: android.content.Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_PROCESS)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: android.content.Context, id: Long) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
