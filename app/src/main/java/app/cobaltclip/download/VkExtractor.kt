package app.cobaltclip.download

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * Прямая экстракция видео из VK через API.
 * Используется когда введен VK access token (для приватных видео).
 */
object VkExtractor {

    data class VkVideo(
        val title: String,
        val url: String,
        val quality: String
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isVkUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("vk.com") || lower.contains("vkvideo.ru") ||
            lower.contains("vk.ru")
    }

    fun extractVideoId(url: String): Pair<Long, Long>? {
        val patterns = listOf(
            // Обычное видео
            Regex("""vk\.(?:com|ru)/video(?:ext\.php\?.*[?&]oid=)?(-?\d+)_(-?\d+)"""),
            Regex("""vkvideo\.ru/video(-?\d+)_(-?\d+)"""),
            Regex("""vk\.(?:com|ru)/.*[?&]z=video(-?\d+)_(-?\d+)"""),
            // Клипы VK
            Regex("""vk\.(?:com|ru)/clip(-?\d+)_(-?\d+)"""),
            Regex("""vkvideo\.ru/clip(-?\d+)_(-?\d+)"""),
            Regex("""vk\.(?:com|ru)/.*[?&]z=clip(-?\d+)_(-?\d+)"""),
            Regex("""oid=(-?\d+).*[?&]id=(-?\d+)""")
        )
        for (p in patterns) {
            p.find(url)?.let { match ->
                val ownerId = match.groupValues[1].toLongOrNull() ?: return@let
                val videoId = match.groupValues[2].toLongOrNull() ?: return@let
                return ownerId to videoId
            }
        }
        return null
    }

    suspend fun resolve(
        url: String,
        accessToken: String,
        quality: String = "max"
    ): List<RemoteFile> {
        val (ownerId, videoId) = extractVideoId(url)
            ?: throw CobaltException("Не удалось распознать VK видео ID из: $url")

        Log.d("VkExtractor", "Resolving VK video: ownerId=$ownerId, videoId=$videoId")

        val apiUrl = "https://api.vk.com/method/video.get?" +
            "videos=${ownerId}_${videoId}" +
            "&access_token=${accessToken}" +
            "&v=5.199"

        val request = Request.Builder()
            .url(apiUrl)
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CobaltException("VK API HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw CobaltException("VK API пустой ответ")

            Log.d("VkExtractor", "VK API response: ${body.take(500)}")

            val json = JSONObject(body)
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                throw CobaltException("VK API: ${error.optString("error_msg", "unknown error")}")
            }

            val responseObj = json.getJSONObject("response")
            val items = responseObj.getJSONArray("items")
            if (items.length() == 0) {
                throw CobaltException("Видео не найдено в VK. Проверьте ссылку и токен.")
            }

            val video = items.getJSONObject(0)
            val title = video.optString("title", "vk_video")
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .take(100)
                .ifBlank { "vk_video" }

            val urls = mutableListOf<VkVideo>()

            // Файлы в поле files
            val files = video.optJSONObject("files")
            if (files != null) {
                listOf("mp4_1080", "mp4_720", "mp4_480", "mp4_360", "mp4_240", "mp4_144").forEach { key ->
                    files.optString(key).takeIf { it.isNotBlank() }?.let {
                        val quality = key.removePrefix("mp4_") + "p"
                        urls += VkVideo(title, it, quality)
                    }
                }
            }

            // Прямые поля на верхнем уровне (fallback)
            if (urls.isEmpty()) {
                listOf("mp4_1080", "mp4_720", "mp4_480", "mp4_360", "mp4_240").forEach { key ->
                    video.optString(key).takeIf { it.isNotBlank() }?.let {
                        val quality = key.removePrefix("mp4_") + "p"
                        urls += VkVideo(title, it, quality)
                    }
                }
            }

            if (urls.isEmpty()) {
                throw CobaltException(
                    "VK API не вернул ссылки на видео. " +
                    "Возможно, видео приватное или токен недостаточен."
                )
            }

            val best = selectQuality(urls, quality)
            Log.d("VkExtractor", "Found ${urls.size} qualities, using ${best.quality}")
            return listOf(RemoteFile(best.url, best.title + ".mp4"))
        }
    }

    /**
     * Выбирает максимальное качество не выше запрошенного.
     * Если все доступные варианты выше запрошенного — берет минимальное.
     */
    internal fun selectQuality(urls: List<VkVideo>, quality: String): VkVideo {
        val requested = quality.trim().removeSuffix("p").toIntOrNull() ?: Int.MAX_VALUE
        val sorted = urls.sortedByDescending { it.quality.removeSuffix("p").toIntOrNull() ?: 0 }
        return sorted.firstOrNull {
            (it.quality.removeSuffix("p").toIntOrNull() ?: 0) <= requested
        } ?: sorted.last()
    }
}
