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
}
