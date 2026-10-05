package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OttCatalog(val type: String, val id: String, val name: String, val searchable: Boolean, val isRow: Boolean)
data class OttTitle(
    val id: String, val type: String, val name: String,
    val poster: String?, val background: String?,
    val description: String?, val year: String?, val rating: String?
)
data class OttEpisode(val id: String, val season: Int, val episode: Int, val title: String, val thumbnail: String?)
data class OttDetail(val title: OttTitle, val genres: List<String>, val runtime: String?, val episodes: List<OttEpisode>)
data class OttStream(val label: String, val detail: String, val url: String, val headers: Map<String, String> = emptyMap())

/**
 * Generic OTT addon that speaks the open Stremio addon protocol
 * (manifest.json + /catalog + /meta + /stream). The user pastes any manifest URL
 * on the sign-in screen; nothing service-specific is baked into the app.
 * Only streams that expose a direct HTTP(S) url are playable (ExoPlayer can't play torrents).
 */
class StremioAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {

    override val description = "Movies & series from any Stremio-compatible addon"
    override val brandColor = Color(0xFFFF8A00)
    override val icon: ImageVector = Icons.Default.Movie
    override val capability = AddonCapability.CONTROL

    private val _state = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _state.asStateFlow()

    var catalogs: List<OttCatalog> = emptyList(); private set
    var addonName: String = displayName; private set
    private var baseUrl: String = ""
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private val prefsKey get() = "ott_manifest_$id"
    private fun prefs() = appContext?.getSharedPreferences("beatraxus_addons", Context.MODE_PRIVATE)

    override fun connect(context: Context) {
        appContext = context.applicationContext
        val saved = prefs()?.getString(prefsKey, null) ?: return
        _state.value = AddonConnectionState.CONNECTING
        scope.launch { loadManifest(saved).onFailure { _state.value = AddonConnectionState.ERROR } }
    }

    override fun disconnect() {
        prefs()?.edit()?.remove(prefsKey)?.apply()
        catalogs = emptyList(); baseUrl = ""
        _state.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}
    override fun buttonLabel() = if (_state.value == AddonConnectionState.CONNECTED) "OPEN" else "SIGN IN"

    /** serverUrl = pasted manifest URL (stremio:// , https://… or …/manifest.json). */
    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        _state.value = AddonConnectionState.CONNECTING
        val r = loadManifest(serverUrl)
        return if (r.isSuccess) {
            prefs()?.edit()?.putString(prefsKey, serverUrl.trim())?.apply()
            Result.success(AuthToken("", baseUrl))
        } else {
            _state.value = AddonConnectionState.ERROR
            Result.failure(r.exceptionOrNull() ?: IOException("Invalid addon URL"))
        }
    }

    private suspend fun loadManifest(raw: String): Result<Unit> = runCatching {
        var u = raw.trim()
        if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
        if (!u.startsWith("http")) u = "https://$u"
        u = u.substringBefore("?").removeSuffix("/").removeSuffix("/manifest.json")
        baseUrl = u
        val m = getJson("$u/manifest.json")
        addonName = m.optString("name", displayName)
        val arr = m.optJSONArray("catalogs") ?: JSONArray()
        catalogs = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val extra = c.optJSONArray("extra") ?: JSONArray()
            var search = false; var required = false
            for (j in 0 until extra.length()) {
                val e = extra.getJSONObject(j)
                if (e.optString("name") == "search") search = true
                if (e.optBoolean("isRequired")) required = true
            }
            val supported = c.optJSONArray("extraSupported")
            if (supported != null) for (j in 0 until supported.length()) if (supported.optString(j) == "search") search = true
            OttCatalog(c.optString("type"), c.optString("id"), c.optString("name", c.optString("id")), search, !required)
        }
        _state.value = AddonConnectionState.CONNECTED
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Beatraxus-OTT/1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body?.string() ?: throw IOException("Empty response"))
        }
    }

    private fun enc(s: String) = android.net.Uri.encode(s)

    private fun parseTitles(arr: JSONArray?, fallbackType: String): List<OttTitle> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OttTitle(
                id = o.optString("id"), type = o.optString("type", fallbackType),
                name = o.optString("name"),
                poster = o.optString("poster").ifBlank { null },
                background = o.optString("background").ifBlank { null },
                description = o.optString("description").ifBlank { null },
                year = o.optString("releaseInfo").ifBlank { o.optString("year") }.ifBlank { null },
                rating = o.optString("imdbRating").ifBlank { null }
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    suspend fun loadCatalog(cat: OttCatalog, skip: Int = 0): List<OttTitle> {
        val extra = if (skip > 0) "/skip=$skip" else ""
        return parseTitles(getJson("$baseUrl/catalog/${cat.type}/${enc(cat.id)}$extra.json").optJSONArray("metas"), cat.type)
    }

    suspend fun searchTitles(query: String): List<OttTitle> {
        val out = mutableListOf<OttTitle>()
        for (c in catalogs.filter { it.searchable }) {
            runCatching {
                out += parseTitles(getJson("$baseUrl/catalog/${c.type}/${enc(c.id)}/search=${enc(query)}.json").optJSONArray("metas"), c.type)
            }
        }
        return out.distinctBy { it.id }
    }

    suspend fun detail(t: OttTitle): OttDetail {
        val m = getJson("$baseUrl/meta/${t.type}/${enc(t.id)}.json").optJSONObject("meta") ?: JSONObject()
        val vids = m.optJSONArray("videos") ?: JSONArray()
        val eps = if (t.type == "series") (0 until vids.length()).mapNotNull { i ->
            val v = vids.getJSONObject(i)
            val s = v.optInt("season", 0); val e = v.optInt("episode", v.optInt("number", 0))
            if (s <= 0 && e <= 0) null
            else OttEpisode(v.optString("id"), s, e, v.optString("title", v.optString("name", "Episode $e")), v.optString("thumbnail").ifBlank { null })
        }.sortedWith(compareBy({ it.season }, { it.episode })) else emptyList()
        val g = m.optJSONArray("genres")
        val full = parseTitles(JSONArray().put(m), t.type).firstOrNull() ?: t
        return OttDetail(
            title = full.copy(poster = full.poster ?: t.poster, background = full.background ?: t.background),
            genres = if (g == null) emptyList() else (0 until g.length()).map { g.optString(it) },
            runtime = m.optString("runtime").ifBlank { null }, episodes = eps
        )
    }

    /** videoId = title id for movies, episode id for series. Direct-URL streams only. */
    suspend fun streams(type: String, videoId: String): List<OttStream> {
        val arr = getJson("$baseUrl/stream/$type/${enc(videoId)}.json").optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val url = s.optString("url")
            if (!url.startsWith("http")) return@mapNotNull null
            val req = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val headers = HashMap<String, String>()
            req?.keys()?.forEach { k -> headers[k] = req.optString(k) }
            OttStream(s.optString("name", addonName).replace("\n", " "), s.optString("title", s.optString("description")).replace("\n", " · "), url, headers)
        }
    }

    // --- MediaServerAddon contract (so existing search/browse plumbing keeps working) ---
    private fun OttTitle.toItem() = AddonMediaItem(id = "$type:$id", title = name, subtitle = year, artworkUrl = poster, mediaType = "video", durationMs = 0L, directPlayUrl = "")

    override suspend fun browse(path: String?): List<AddonMediaItem> =
        catalogs.firstOrNull { it.isRow }?.let { loadCatalog(it).map { t -> t.toItem() } } ?: emptyList()

    // Global app search skips OTT results: a tapped result may have no direct stream. Search lives inside the OTT screen.
    override suspend fun search(query: String): List<AddonMediaItem> = emptyList()

    override suspend fun streamUrl(item: AddonMediaItem): String {
        if (item.directPlayUrl.isNotBlank()) return item.directPlayUrl
        val type = item.id.substringBefore(":"); val vid = item.id.substringAfter(":")
        return streams(type, vid).firstOrNull()?.url ?: throw IOException("No playable stream")
    }
}

/** Like runCatching, but never swallows coroutine cancellation (so LaunchedEffect restarts don't write stale state). */
inline fun <T> ottCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
