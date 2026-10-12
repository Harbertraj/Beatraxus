package com.beatraxus.app.addons

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Pure helpers for the Emby REST API (no Android classes, so they can be unit-tested on a JVM).
 */
internal object EmbyApi {

    const val AUTH_HEADER =
        "MediaBrowser Client=\"Beatraxus\", Device=\"Android\", DeviceId=\"beatraxus-app\", Version=\"1.0.0\""

    /** "emby.local:8096/" -> "http://emby.local:8096/emby" (Emby serves its API under /emby). */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
        u = u.trimEnd('/')
        if (!u.endsWith("/emby", ignoreCase = true)) u += "/emby"
        return u
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun itemsUrl(base: String, userId: String, query: String?, limit: Int = 60): String {
        val common = "Recursive=true&IncludeItemTypes=Audio,Movie,Episode&Limit=$limit&ImageTypeLimit=1"
        return if (query.isNullOrBlank()) {
            "$base/Users/$userId/Items?$common&SortBy=DateCreated&SortOrder=Descending"
        } else {
            "$base/Users/$userId/Items?$common&SearchTerm=${enc(query.trim())}"
        }
    }

    fun streamUrl(base: String, token: String, itemId: String, mediaType: String): String {
        val endpoint = if (mediaType == "audio") "Audio" else "Videos"
        // static=true = direct play of the original file, no server-side transcode.
        return "$base/$endpoint/${enc(itemId)}/stream?static=true&api_key=${enc(token)}"
    }

    fun parseItems(json: JSONObject, base: String): List<AddonMediaItem> {
        val items = json.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("Id")
            if (id.isBlank()) return@mapNotNull null
            val type = item.optString("Type")
            val isAudio = type == "Audio"
            val artists = item.optJSONArray("Artists")
            val artist = if (artists != null && artists.length() > 0) artists.optString(0) else ""
            val subtitle = when {
                isAudio -> listOf(artist, item.optString("Album")).filter { it.isNotBlank() }.joinToString(" · ")
                type == "Episode" -> item.optString("SeriesName")
                else -> item.optInt("ProductionYear", 0).takeIf { it > 0 }?.toString().orEmpty()
            }
            AddonMediaItem(
                id = id,
                title = item.optString("Name").ifBlank { "Unknown" },
                subtitle = subtitle.ifBlank { null },
                artworkUrl = "$base/Items/$id/Images/Primary?maxHeight=400&maxWidth=400",
                mediaType = if (isAudio) "audio" else "video",
                durationMs = item.optLong("RunTimeTicks", 0L) / 10_000L,
                directPlayUrl = ""
            )
        }
    }
}
