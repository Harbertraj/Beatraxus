package com.beatraxus.app.livetv

import android.net.Uri
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import java.util.Locale

/**
 * One channel from a playlist the user added.
 *
 * [id] is stable across refreshes (the playlist's `tvg-id`, or a hash of the stream address when a channel has none),
 * so favourites and recents keep matching after a playlist update.
 */
data class LiveChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    /** First `group-title` of the playlist entry (e.g. "News"), "" when absent. */
    val group: String = "",
    /** ISO 3166-1 alpha-2 country code taken from the `tvg-id` suffix (e.g. "IN"), null when unknown. */
    val countryCode: String? = null,
    /** Vertical resolution parsed from the "(720p)" tag in the channel name, 0 when unknown. */
    val qualityHeight: Int = 0,
    val isGeoBlocked: Boolean = false,
    val isNot247: Boolean = false,
    val referrer: String? = null,
    val userAgent: String? = null
) {
    /** Id the video player knows this channel by. */
    val videoId: String get() = "live_$id"

    val qualityLabel: String?
        get() = when {
            qualityHeight >= 2160 -> "4K"
            qualityHeight >= 1080 -> "FHD"
            qualityHeight >= 720 -> "HD"
            qualityHeight > 0 -> "SD"
            else -> null
        }

    /** Two-letter monogram shown when a channel has no logo. */
    val monogram: String
        get() {
            val words = name.split(' ', '-', '_').filter { w -> w.isNotBlank() && w.first().isLetterOrDigit() }
            return when {
                words.isEmpty() -> "TV"
                words.size == 1 -> words[0].take(2).uppercase(Locale.ROOT)
                else -> "${words[0].first()}${words[1].first()}".uppercase(Locale.ROOT)
            }
        }
}

/** Header values some streams insist on (playlists list them as `#EXTVLCOPT` lines). */
internal val LiveChannel.requestHeaders: Map<String, String>
    get() = buildMap {
        referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    }

/** The channel as a [Video] the existing video player can queue; next / previous then zap between channels. */
fun LiveChannel.toVideo(): Video = Video(
    id = videoId,
    uri = Uri.parse(streamUrl),
    title = name,
    displayName = name,
    folderPath = "livetv",
    durationMs = 0L,
    sizeBytes = 0L,
    resolutionWidth = 0,
    resolutionHeight = qualityHeight,
    mimeType = "application/x-mpegURL",
    dateAdded = 0L,
    thumbnailUri = logoUrl?.let { Uri.parse(it) },
    source = VideoSource.LIVE_TV,
    sourceId = group.ifBlank { null }
)

/** What a playlist's channels can be narrowed by; only the ones the playlist really offers are shown. */
enum class LiveTvTab(val label: String) {
    CATEGORY("Categories"),
    COUNTRY("Countries")
}

internal fun shortHash(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

/** One selectable chip (a country, category or language) and the playlist code behind it. */
data class LiveFilter(val code: String, val label: String, val emoji: String)

/** What the repository loads: a playlist link the user added, or `file:` + path of an M3U file they imported. */
data class LiveSource(val url: String) {
    /** File name for the cached copy of this playlist. */
    val cacheKey: String get() = shortHash(url)
}

/** A playlist the user added by pasting its address. The app ships with none. */
data class SavedPlaylist(val url: String, val name: String)

/** Accepts only plain http(s) addresses with a host; returns the trimmed address or null. */
internal fun cleanPlaylistUrl(raw: String): String? {
    val text = raw.trim()
    if (text.length > 2000 || text.any { it.isWhitespace() }) return null
    if (!text.startsWith("https://", ignoreCase = true) && !text.startsWith("http://", ignoreCase = true)) return null
    val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
    return if (uri.host.isNullOrBlank() || uri.userInfo != null) null else text
}

/** A short label for a playlist chip: the file name when it says something, else the host. */
internal fun playlistLabel(url: String): String {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    val file = uri?.path.orEmpty().substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
    val generic = file.isEmpty() || file.lowercase(Locale.ROOT) in setOf("index", "playlist", "list", "channels", "tv", "get")
    return if (generic) (uri?.host ?: "Playlist") else file
}

internal fun flagEmoji(countryCode: String?): String {
    val c = (countryCode ?: return "🌐").uppercase(Locale.ROOT).let { if (it == "UK") "GB" else it }
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🌐"
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + c[0].code)) + String(Character.toChars(base + c[1].code))
}

/** What the player gets when a channel is opened: the channels around it as a queue, and which one starts. */
class LiveQueue(val videos: List<Video>, val startVideoId: String)

object LiveTvPlayback {

    /** Channels on each side of the tapped one that stay reachable with next / previous. */
    private const val ZAP_RADIUS = 150

    /**
     * Builds the player queue for [startIndex] in [channels] and registers the Referer / User-Agent headers the
     * streams ask for. Returns null for an empty list.
     */
    fun queue(channels: List<LiveChannel>, startIndex: Int): LiveQueue? {
        if (channels.isEmpty()) return null
        val start = startIndex.coerceIn(0, channels.lastIndex)
        val window = channels.subList(
            (start - ZAP_RADIUS).coerceAtLeast(0),
            (start + ZAP_RADIUS + 1).coerceAtMost(channels.size)
        )
        window.forEach { registerHeaders(it) }
        return LiveQueue(window.map { it.toVideo() }, channels[start].videoId)
    }

    private fun registerHeaders(channel: LiveChannel) {
        val headers = channel.requestHeaders
        if (headers.isEmpty()) return
        com.beatraxus.app.addons.AddonStreamHeaders.register(channel.streamUrl, headers)
        runCatching { Uri.parse(channel.streamUrl).host }.getOrNull()
            ?.let { com.beatraxus.app.addons.AddonStreamHeaders.registerHost(it, headers) }
    }

    /** `application/x-mpegURL` for streams whose address doesn't end in .m3u8 but is one (query-string playlists). */
    fun mimeTypeFor(uri: Uri): String? {
        val text = uri.toString().lowercase(java.util.Locale.ROOT)
        val path = (uri.path ?: "").lowercase(java.util.Locale.ROOT)
        return when {
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> null // ExoPlayer already recognises these
            text.contains("m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }
}
