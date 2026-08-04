package app.cobaltclip.download

object UrlMatcher {
    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val bareVkRegex = Regex(
        """(?:^|\s)((?:vk\.com|vk\.ru|vkvideo\.ru)/[^\s<>"]+)""",
        RegexOption.IGNORE_CASE
    )
    private val hosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
        "instagram.com", "www.instagram.com",
        "bilibili.com", "b23.tv", "bsky.app", "dailymotion.com", "dai.ly",
        "facebook.com", "fb.watch", "loom.com", "ok.ru", "pinterest.com",
        "pin.it", "reddit.com", "redd.it", "rutube.ru", "snapchat.com",
        "soundcloud.com", "streamable.com", "tiktok.com", "vm.tiktok.com",
        "tumblr.com", "twitch.tv", "clips.twitch.tv", "x.com", "twitter.com",
        "vimeo.com", "vk.com", "vk.ru", "vkvideo.ru"
    )

    private fun String.extractHost(): String? {
        val afterProto = substringAfter("://", "")
        if (afterProto.isEmpty()) return null
        return afterProto.substringBefore("/").substringBefore(":").lowercase()
    }

    private fun String.isSupported(): Boolean {
        val host = extractHost() ?: return false
        return host in hosts || hosts.any { host.endsWith(".$it") }
    }

    private fun String.cleanTrailing(): String =
        trimEnd('.', ',', ')', ']', '}', ';')

    fun extractAll(text: String?): List<String> {
        val normal = urlRegex.findAll(text.orEmpty())
            .map { it.value.cleanTrailing() }
            .filter { it.isSupported() }
        val bareVk = bareVkRegex.findAll(text.orEmpty())
            .map { "https://${it.groupValues[1].cleanTrailing()}" }
            .filter { it.isSupported() }
        return (normal + bareVk).distinct().toList()
    }

    fun extract(text: String?): String? = extractAll(text).firstOrNull()
}
