package app.cobaltclip.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlMatcherTest {
    @Test
    fun extractsSupportedLinksAndRemovesDuplicates() {
        val text = """
            https://www.instagram.com/reel/example/?igsh=test
            https://youtu.be/example
            https://youtu.be/example
            https://www.tiktok.com/@user/video/123
        """.trimIndent()

        assertEquals(
            listOf(
                "https://www.instagram.com/reel/example/?igsh=test",
                "https://youtu.be/example",
                "https://www.tiktok.com/@user/video/123"
            ),
            UrlMatcher.extractAll(text)
        )
    }

    @Test
    fun supportsSubdomainsOfKnownServices() {
        assertEquals(
            "https://music.youtube.com/watch?v=example",
            UrlMatcher.extract("https://music.youtube.com/watch?v=example")
        )
    }

    @Test
    fun ignoresUnsupportedHosts() {
        assertNull(UrlMatcher.extract("https://example.com/video"))
    }

    @Test
    fun supportsVkDomains() {
        assertEquals(
            "https://vk.com/video-12345_67890",
            UrlMatcher.extract("https://vk.com/video-12345_67890")
        )
        assertEquals(
            "https://vkvideo.ru/video-12345_67890",
            UrlMatcher.extract("https://vkvideo.ru/video-12345_67890")
        )
        assertEquals(
            "https://m.vk.com/video-12345_67890",
            UrlMatcher.extract("https://m.vk.com/video-12345_67890")
        )
        assertEquals(
            "https://vk.ru/video-12345_67890",
            UrlMatcher.extract("https://vk.ru/video-12345_67890")
        )
        assertEquals(
            "https://vk.ru/clip-12345_67890",
            UrlMatcher.extract("https://vk.ru/clip-12345_67890")
        )
    }

    @Test
    fun supportsBareVkUrls() {
        assertEquals(
            "https://vk.com/video-12345_67890",
            UrlMatcher.extract("Посмотри vk.com/video-12345_67890")
        )
        assertEquals(
            "https://vkvideo.ru/video-12345_67890",
            UrlMatcher.extract("Видео тут: vkvideo.ru/video-12345_67890")
        )
        assertEquals(
            "https://vk.ru/video-12345_67890",
            UrlMatcher.extract("Ссылка vk.ru/video-12345_67890")
        )
    }
}
