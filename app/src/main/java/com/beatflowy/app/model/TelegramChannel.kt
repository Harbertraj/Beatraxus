package com.beatflowy.app.model

data class TelegramChannel(
    val url: String,       // e.g. https://t.me/channelname
    val name: String,      // display name parsed from URL
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

fun parseTelegramChannelName(url: String): String {
    // Extract the last path segment from t.me/xxx or @xxx
    return url.trim()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("t.me/").removePrefix("@")
        .split("/").first()
        .ifBlank { url }
}
