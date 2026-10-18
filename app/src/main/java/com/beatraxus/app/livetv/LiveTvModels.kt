package com.beatraxus.app.livetv

import android.net.Uri
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import java.util.Locale

/**
 * One live channel from the iptv-org playlists (https://github.com/iptv-org/iptv).
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

/** Header values some streams insist on (iptv-org lists them as `#EXTVLCOPT` lines). */
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

/** How the playlists are grouped on iptv-org. */
enum class LiveTvTab(val label: String, val path: String) {
    COUNTRY("Countries", "countries"),
    CATEGORY("Categories", "categories"),
    LANGUAGE("Languages", "languages")
}

/** One selectable chip (a country, category or language) and the playlist code behind it. */
data class LiveFilter(val code: String, val label: String, val emoji: String)

/** What the repository loads: the playlist for [code] inside [tab]. */
data class LiveSource(val tab: LiveTvTab, val code: String) {
    val url: String get() = "https://iptv-org.github.io/iptv/${tab.path}/${code.lowercase(Locale.ROOT)}.m3u"
    val cacheKey: String get() = "${tab.path}_${code.lowercase(Locale.ROOT)}"
}

internal fun flagEmoji(countryCode: String?): String {
    val c = (countryCode ?: return "🌐").uppercase(Locale.ROOT).let { if (it == "UK") "GB" else it }
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🌐"
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + c[0].code)) + String(Character.toChars(base + c[1].code))
}

/** Curated chips; every code here has a playlist on iptv-org. Order = display order (device country goes first). */
object LiveTvCatalog {

    private val countryCodes = listOf(
        "in", "us", "gb", "ca", "au", "ae", "sa", "pk", "bd", "lk", "np", "sg", "my", "id", "ph", "th", "vn",
        "jp", "kr", "cn", "hk", "tw", "de", "fr", "es", "it", "pt", "nl", "be", "ch", "at", "se", "no", "dk",
        "fi", "ie", "pl", "ro", "gr", "tr", "ru", "ua", "il", "ir", "iq", "eg", "ma", "dz", "za", "ng", "ke",
        "gh", "br", "mx", "ar", "co", "cl", "pe", "ve"
    )

    private val languages = listOf(
        "tam" to "Tamil", "hin" to "Hindi", "eng" to "English", "tel" to "Telugu", "mal" to "Malayalam",
        "kan" to "Kannada", "ben" to "Bengali", "mar" to "Marathi", "guj" to "Gujarati", "pan" to "Punjabi",
        "urd" to "Urdu", "ara" to "Arabic", "spa" to "Spanish", "fra" to "French", "por" to "Portuguese",
        "deu" to "German", "ita" to "Italian", "rus" to "Russian", "tur" to "Turkish", "zho" to "Chinese",
        "jpn" to "Japanese", "kor" to "Korean", "ind" to "Indonesian", "tha" to "Thai", "vie" to "Vietnamese",
        "fil" to "Filipino", "fas" to "Persian", "heb" to "Hebrew", "pol" to "Polish", "ukr" to "Ukrainian"
    )

    // `xxx` and `undefined` are intentionally left out (adult content is no longer distributed; undefined is noise).
    private val categories = listOf(
        "news" to "📰", "entertainment" to "🎭", "movies" to "🎬",
        "series" to "📺", "sports" to "⚽", "music" to "🎵", "kids" to "🧸",
        "animation" to "🎨", "documentary" to "🌍", "general" to "📡",
        "comedy" to "😂", "lifestyle" to "✨", "cooking" to "🍳",
        "travel" to "✈️", "science" to "🔬", "education" to "🎓",
        "business" to "📈", "religious" to "🕊️", "culture" to "🏛️",
        "family" to "👪", "classic" to "📽️", "outdoor" to "🏕️",
        "auto" to "🚗", "weather" to "⛅", "relax" to "🧘", "public" to "🏢",
        "legislative" to "⚖️", "shop" to "🛍️", "interactive" to "🎮"
    )

    fun filters(tab: LiveTvTab): List<LiveFilter> = when (tab) {
        LiveTvTab.COUNTRY -> {
            val device = deviceCountry()
            val ordered = if (device in countryCodes) listOf(device) + countryCodes.filter { it != device } else countryCodes
            ordered.map { code ->
                val name = Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH).ifBlank { code.uppercase(Locale.ROOT) }
                LiveFilter(code, name, flagEmoji(code))
            }
        }
        LiveTvTab.CATEGORY -> categories.map { (slug, emoji) ->
            LiveFilter(slug, slug.replaceFirstChar { it.titlecase(Locale.ROOT) }, emoji)
        }
        LiveTvTab.LANGUAGE -> {
            val device = deviceLanguage()
            val ordered = languages.sortedBy { if (it.first == device) 0 else 1 }
            ordered.map { (code, label) -> LiveFilter(code, label, "🗣️") }
        }
    }

    /** First chip of [tab]; for countries and languages that is the device's own region / language when it has a playlist. */
    fun defaultCode(tab: LiveTvTab): String = filters(tab).first().code

    private fun deviceCountry(): String = Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" }

    private fun deviceLanguage(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "ta" -> "tam"
        "hi" -> "hin"
        "te" -> "tel"
        "ml" -> "mal"
        "kn" -> "kan"
        "bn" -> "ben"
        "mr" -> "mar"
        "gu" -> "guj"
        "pa" -> "pan"
        "ur" -> "urd"
        "ar" -> "ara"
        "es" -> "spa"
        "fr" -> "fra"
        "pt" -> "por"
        "de" -> "deu"
        "it" -> "ita"
        "ru" -> "rus"
        "tr" -> "tur"
        "zh" -> "zho"
        "ja" -> "jpn"
        "ko" -> "kor"
        "id" -> "ind"
        "th" -> "tha"
        "vi" -> "vie"
        else -> "eng"
    }
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
