package com.beatraxus.app.model

data class TelegramChannel(
    val url: String,       // e.g. https://t.me/channelname
    val name: String,      // display name parsed from URL
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSyncTimestamp: Long = 0L
)

fun parseTelegramChannelName(url: String): String {
    val trimmed = url.trim()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("t.me/").removePrefix("telegram.me/")
        .removePrefix("@")
        
    if (trimmed.startsWith("c/")) {
        // Private channel link: t.me/c/123456789/1
        val parts = trimmed.split("/")
        if (parts.size >= 2) {
            val id = parts[1]
            if (id.toLongOrNull() != null) {
                return "-100$id"
            }
        }
    }

    if (trimmed.startsWith("s/")) {
        // Preview link: t.me/s/SomeChannel
        return trimmed.removePrefix("s/").split("/").first()
    }

    if (trimmed.startsWith("+")) {
        // Join link: t.me/+AbCdEf
        return trimmed
    }
    
    return trimmed.split("/").first().ifBlank { url }
}
