package app.cobaltclip.download

import app.cobaltclip.data.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.delay

data class RemoteFile(val url: String, val filename: String, val type: String? = null)

class CobaltException(message: String) : Exception(message)

private class CobaltApiException(val code: String) : Exception(code)

class CobaltClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    suspend fun resolve(source: String, settings: UserSettings): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val endpoint = settings.endpoint.trim().trimEnd('/')
            if (endpoint.isBlank()) throw CobaltException(
                "Укажите адрес собственного или разрешенного экземпляра cobalt API в настройках"
            )
            if (!endpoint.startsWith("https://")) {
                throw CobaltException("Адрес API должен начинаться с https://")
            }
            try {
                retryTransient {
                    resolveOnce(endpoint, source, settings)
                }
            } catch (error: CobaltApiException) {
                val sourceWithoutQuery = instagramUrlWithoutQuery(source)
                if (
                    error.code == "error.api.fetch.empty" &&
                    sourceWithoutQuery != null
                ) {
                    try {
                        retryTransient {
                            resolveOnce(endpoint, sourceWithoutQuery, settings)
                        }
                    } catch (retryError: CobaltApiException) {
                        throw CobaltException(messageForApiError(retryError.code))
                    }
                } else {
                    throw CobaltException(messageForApiError(error.code))
                }
            }
        }

    private fun resolveOnce(
        endpoint: String,
        source: String,
        settings: UserSettings
    ): List<RemoteFile> {
        val json = JSONObject()
            .put("url", source)
            .put("downloadMode", settings.downloadMode)
            .put("videoQuality", settings.quality)
            .put("youtubeVideoCodec", "h264")
            .put("youtubeVideoContainer", "mp4")
            .put("filenameStyle", "pretty")
            .put("localProcessing", "disabled")
        val request = Request.Builder()
            .url("$endpoint/")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .apply {
                if (settings.apiKey.isNotBlank()) {
                    header("Authorization", "Api-Key ${settings.apiKey}")
                }
            }.build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val result = runCatching { JSONObject(body) }.getOrNull()
            val errorCode = result
                ?.optJSONObject("error")
                ?.optString("code")
                .orEmpty()
            if (
                result?.optString("status") == "error" ||
                (!response.isSuccessful && errorCode.isNotBlank())
            ) {
                throw CobaltApiException(errorCode.ifBlank { "unknown" })
            }
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    401 -> "Неверный или отсутствующий ключ API"
                    403 -> "Сервер отклонил запрос"
                    429 -> "Превышен лимит запросов"
                    else -> "Ошибка API HTTP ${response.code}"
                }
                if (response.code == 429 || response.code in 500..599) {
                    throw TransientHttpException(
                        hint,
                        response.header("Retry-After")?.toLongOrNull()
                    )
                }
                throw CobaltException(hint)
            }
            if (result == null) {
                throw CobaltException("Сервер вернул некорректный JSON")
            }
            return when (result.optString("status")) {
                "tunnel", "redirect" -> listOf(
                    RemoteFile(result.getString("url"), result.optString("filename", "media"))
                )
                "picker" -> {
                    val items = result.getJSONArray("picker")
                    (0 until items.length()).map { index ->
                        val item = items.getJSONObject(index)
                        RemoteFile(
                            item.getString("url"),
                            "media_${index + 1}.${extensionFor(item.optString("type"))}",
                            item.optString("type")
                        )
                    }
                }
                "local-processing" -> throw CobaltException(
                    "Сервер запросил локальную обработку видео, которая не поддерживается приложением"
                )
                else -> throw CobaltException("Неизвестный ответ cobalt")
            }
        }
    }

    private fun instagramUrlWithoutQuery(source: String): String? {
        val url = source.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return null
        if (url.query == null) return null
        return url.newBuilder().query(null).build().toString()
    }

    private fun messageForApiError(code: String) = when (code) {
        "error.api.fetch.empty" ->
            "Публикация недоступна или приватна. Для Instagram серверу cobalt могут потребоваться cookies."
        else -> "cobalt: $code"
    }

    fun newDownloadRequest(url: String, offset: Long = 0) =
        Request.Builder()
            .url(url)
            .get()
            .apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }
            .build()
    fun execute(request: Request) = http.newCall(request).execute()

    suspend fun <T> retryTransient(
        attempts: Int = 4,
        block: () -> T
    ): T {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (error: Exception) {
                if (error !is IOException && error !is TransientHttpException) throw error
                lastError = error
                if (attempt == attempts - 1) throw error
                val serverDelay = (error as? TransientHttpException)?.retryAfterSeconds
                    ?.times(1_000)
                val exponentialDelay = min(1_000L shl attempt, 8_000L)
                delay(serverDelay?.coerceAtMost(30_000L) ?: exponentialDelay)
            }
        }
        throw lastError ?: CobaltException("Не удалось выполнить запрос")
    }

    private fun extensionFor(type: String) = when (type) {
        "photo" -> "jpg"
        "gif" -> "gif"
        else -> "mp4"
    }
}

class TransientHttpException(
    message: String,
    val retryAfterSeconds: Long? = null
) : IOException(message)
