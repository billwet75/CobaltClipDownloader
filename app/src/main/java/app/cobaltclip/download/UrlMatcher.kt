package app.cobaltclip.download

object UrlMatcher {
    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val hosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
        "instagram.com", "www.instagram.com"
    )

    fun extract(text: String?): String? = urlRegex.findAll(text.orEmpty())
        .map { it.value.trimEnd('.', ',', ')', ']', '}', ';') }
        .firstOrNull { value ->
            runCatching {
                val host = java.net.URI(value).host?.lowercase()
                host in hosts || host?.endsWith(".youtube.com") == true ||
                    host?.endsWith(".instagram.com") == true
            }.getOrDefault(false)
        }
}
