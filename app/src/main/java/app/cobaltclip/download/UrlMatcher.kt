package app.cobaltclip.download

object UrlMatcher {
    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val hosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
        "instagram.com", "www.instagram.com",
        "bilibili.com", "b23.tv", "bsky.app", "dailymotion.com", "dai.ly",
        "facebook.com", "fb.watch", "loom.com", "ok.ru", "pinterest.com",
        "pin.it", "reddit.com", "redd.it", "rutube.ru", "snapchat.com",
        "soundcloud.com", "streamable.com", "tiktok.com", "vm.tiktok.com",
        "tumblr.com", "twitch.tv", "clips.twitch.tv", "x.com", "twitter.com",
        "vimeo.com", "vk.com"
    )

    fun extractAll(text: String?): List<String> = urlRegex.findAll(text.orEmpty())
        .map { it.value.trimEnd('.', ',', ')', ']', '}', ';') }
        .filter { value ->
            runCatching {
                val host = java.net.URI(value).host?.lowercase()
                host in hosts || hosts.any { host?.endsWith(".$it") == true }
            }.getOrDefault(false)
        }
        .distinct()
        .toList()

    fun extract(text: String?): String? = extractAll(text).firstOrNull()
}
