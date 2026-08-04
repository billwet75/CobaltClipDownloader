package app.cobaltclip.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VkExtractorTest {

    @Test
    fun detectsVkUrls() {
        listOf(
            "https://vk.com/video-123_456",
            "https://m.vk.com/video-123_456",
            "https://vk.ru/video-123_456",
            "https://vkvideo.ru/video-123_456",
            "https://vk.com/clip-123_456"
        ).forEach { url ->
            assertEquals("Для $url", true, VkExtractor.isVkUrl(url))
        }
    }

    @Test
    fun rejectsNonVkUrls() {
        listOf(
            "https://youtube.com/watch?v=abc",
            "https://example.com/video"
        ).forEach { url ->
            assertEquals("Для $url", false, VkExtractor.isVkUrl(url))
        }
    }

    @Test
    fun extractsVideoIds() {
        val cases = mapOf(
            "https://vk.com/video-12345_67890" to (-12345L to 67890L),
            "https://vk.ru/video-12345_67890" to (-12345L to 67890L),
            "https://m.vk.com/video-12345_67890?list=abc" to (-12345L to 67890L),
            "https://vkvideo.ru/video-12345_67890" to (-12345L to 67890L),
            "https://vk.com/clip-12345_67890" to (-12345L to 67890L),
            "https://vkvideo.ru/clip-12345_67890" to (-12345L to 67890L),
            "https://vk.ru/clip-12345_67890" to (-12345L to 67890L),
            "https://vk.com/video?z=video-12345_67890" to (-12345L to 67890L),
            "https://vk.com/clips/user?z=clip-12345_67890" to (-12345L to 67890L),
            "https://vk.com/video_ext.php?oid=-12345&id=67890&hash=abc" to (-12345L to 67890L)
        )
        cases.forEach { (url, expected) ->
            assertEquals("Для $url", expected, VkExtractor.extractVideoId(url))
        }
    }

    @Test
    fun returnsNullForUnrelatedVkPages() {
        assertNull(VkExtractor.extractVideoId("https://vk.com/wall-12345_67890"))
        assertNull(VkExtractor.extractVideoId("https://vk.com/feed"))
    }

    @Test
    fun selectsRequestedOrLowerQuality() {
        val urls = listOf(
            VkExtractor.VkVideo("t", "u1080", "1080p"),
            VkExtractor.VkVideo("t", "u720", "720p"),
            VkExtractor.VkVideo("t", "u360", "360p")
        )
        assertEquals("u720", VkExtractor.selectQuality(urls, "720").url)
        assertEquals("u1080", VkExtractor.selectQuality(urls, "max").url)
        assertEquals("u1080", VkExtractor.selectQuality(urls, "2160").url)
        // Запрошено ниже минимального — берем минимальное доступное
        assertEquals("u360", VkExtractor.selectQuality(urls, "144").url)
        // Нет точного совпадения — ближайшее не выше запрошенного
        assertEquals("u360", VkExtractor.selectQuality(urls, "480").url)
    }
}
