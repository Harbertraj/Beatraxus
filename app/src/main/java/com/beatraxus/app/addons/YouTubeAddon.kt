package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

data class YtVideo(
    val id: String, val title: String, val channel: String, val thumbnail: String?,
    val duration: String, val views: String, val age: String, val description: String
)

/**
 * YouTube addon. Browse/search use the OFFICIAL YouTube Data API v3 with the user's own API key,
 * and playback happens in YouTube's OFFICIAL embedded player (IFrame API). Nothing is extracted
 * from YouTube and no ads are removed — ads (if any) are shown by the official player.
 */
class YouTubeAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {

    override val description = "YouTube in the official player (needs your free YouTube Data API key)"
    override val brandColor = Color(0xFFFF0000)
    override val icon: ImageVector = Icons.Default.PlayCircle
    override val capability = AddonCapability.CONTROL

    private val _state = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _state.asStateFlow()

    private var apiKey = ""
    private var appContext: Context? = null
    private val region: String = Locale.getDefault().country.ifBlank { "US" }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private val prefsKey get() = "yt_api_key_$id"
    private fun prefs() = appContext?.getSharedPreferences("beatraxus_addons", Context.MODE_PRIVATE)

    override fun connect(context: Context) {
        appContext = context.applicationContext
        apiKey = prefs()?.getString(prefsKey, null).orEmpty()
        // No network call here (saves API quota); bad keys surface as an error on the home screen.
        _state.value = if (apiKey.isNotBlank()) AddonConnectionState.CONNECTED else AddonConnectionState.NOT_CONNECTED
    }

    override fun disconnect() {
        prefs()?.edit()?.remove(prefsKey)?.apply()
        apiKey = ""
        _state.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}
    override fun buttonLabel() = if (_state.value == AddonConnectionState.CONNECTED) "OPEN" else "SIGN IN"

    /** The sign-in screen passes the API key as `password`. */
    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        val key = password.trim()
        if (key.isBlank()) return Result.failure(IOException("Enter your YouTube Data API key"))
        _state.value = AddonConnectionState.CONNECTING
        val previous = apiKey
        apiKey = key
        return try {
            get("videos", mapOf("part" to "id", "chart" to "mostPopular", "maxResults" to "1", "regionCode" to region))
            prefs()?.edit()?.putString(prefsKey, key)?.apply()
            _state.value = AddonConnectionState.CONNECTED
            Result.success(AuthToken(key, "https://www.googleapis.com"))
        } catch (e: IOException) {
            apiKey = previous
            _state.value = if (previous.isNotBlank()) AddonConnectionState.CONNECTED else AddonConnectionState.ERROR
            Result.failure(e)
        }
    }

    private fun enc(s: String) = android.net.Uri.encode(s)

    private suspend fun get(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val query = (params + ("key" to apiKey)).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val req = Request.Builder().url("https://www.googleapis.com/youtube/v3/$path?$query").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            if (!r.isSuccessful) {
                throw IOException(json?.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "HTTP ${r.code}")
            }
            json ?: throw IOException("Bad response from YouTube API")
        }
    }

    /** Most popular videos in the device's region, optionally within a category (e.g. "10" = Music). */
    suspend fun trending(categoryId: String? = null): List<YtVideo> {
        val p = mutableMapOf("part" to "snippet,contentDetails,statistics", "chart" to "mostPopular", "maxResults" to "30", "regionCode" to region)
        if (categoryId != null) p["videoCategoryId"] = categoryId
        return parseVideos(get("videos", p).optJSONArray("items"))
    }

    /** search.list (100 quota units) + videos.list (1 unit) for duration/views. */
    suspend fun searchVideos(query: String): List<YtVideo> {
        val s = get("search", mapOf("part" to "snippet", "type" to "video", "maxResults" to "25", "q" to query, "regionCode" to region))
        val items = s.optJSONArray("items") ?: return emptyList()
        val ids = (0 until items.length()).mapNotNull { items.getJSONObject(it).optJSONObject("id")?.optString("videoId")?.ifBlank { null } }
        if (ids.isEmpty()) return emptyList()
        val v = get("videos", mapOf("part" to "snippet,contentDetails,statistics", "id" to ids.joinToString(",")))
        val byId = parseVideos(v.optJSONArray("items")).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun parseVideos(arr: JSONArray?): List<YtVideo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val vid = o.optString("id")
            val sn = o.optJSONObject("snippet") ?: return@mapNotNull null
            if (vid.isBlank()) return@mapNotNull null
            val th = sn.optJSONObject("thumbnails")
            val thumb = (th?.optJSONObject("high") ?: th?.optJSONObject("medium") ?: th?.optJSONObject("default"))?.optString("url")?.ifBlank { null }
            val live = sn.optString("liveBroadcastContent") == "live"
            YtVideo(
                id = vid, title = sn.optString("title"), channel = sn.optString("channelTitle"), thumbnail = thumb,
                duration = if (live) "LIVE" else formatDuration(o.optJSONObject("contentDetails")?.optString("duration").orEmpty()),
                views = o.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull()?.let { formatViews(it) }.orEmpty(),
                age = formatAge(sn.optString("publishedAt")),
                description = sn.optString("description")
            )
        }
    }

    // --- MediaServerAddon contract. Global app search intentionally skips YouTube (it would burn API quota
    //     on every keystroke and YouTube videos only play inside the official player screen). ---
    override suspend fun browse(path: String?): List<AddonMediaItem> = emptyList()
    override suspend fun search(query: String): List<AddonMediaItem> = emptyList()
    override suspend fun streamUrl(item: AddonMediaItem): String =
        throw UnsupportedOperationException("YouTube plays in its official player")

    companion object {
        fun formatDuration(iso: String): String {
            val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(iso) ?: return ""
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val s = m.groupValues[3].toIntOrNull() ?: 0
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, min, s) else String.format(Locale.US, "%d:%02d", min, s)
        }

        fun formatViews(n: Long): String {
            fun f(v: Double, suffix: String) = (String.format(Locale.US, "%.1f", v).removeSuffix(".0")) + suffix + " views"
            return when {
                n >= 1_000_000_000 -> f(n / 1e9, "B")
                n >= 1_000_000 -> f(n / 1e6, "M")
                n >= 1_000 -> f(n / 1e3, "K")
                else -> "$n views"
            }
        }

        fun formatAge(iso: String): String = try {
            val d = Duration.between(Instant.parse(iso), Instant.now())
            val days = d.toDays()
            when {
                days >= 365 -> "${days / 365} year${if (days / 365 > 1) "s" else ""} ago"
                days >= 30 -> "${days / 30} month${if (days / 30 > 1) "s" else ""} ago"
                days >= 1 -> "$days day${if (days > 1) "s" else ""} ago"
                d.toHours() >= 1 -> "${d.toHours()} hour${if (d.toHours() > 1) "s" else ""} ago"
                else -> "${maxOf(d.toMinutes(), 1)} min ago"
            }
        } catch (_: Exception) { "" }
    }
}
