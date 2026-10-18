package com.beatraxus.app.livetv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Loads the M3U playlists the user added (the app ships with none) and keeps their favourite / recently watched
 * channels.
 *
 * Playlists are cached on disk for [CACHE_TTL_MS]; when the network is down a stale copy is still used, so the channel
 * list opens instantly and keeps working offline (the streams themselves obviously need a network).
 */
class LiveTvRepository(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("livetv", Context.MODE_PRIVATE)
    private val cacheDir = File(app.cacheDir, "livetv").apply { mkdirs() }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val memory = object : LinkedHashMap<String, List<LiveChannel>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LiveChannel>>?): Boolean = size > MEMORY_LISTS
    }

    suspend fun load(source: LiveSource, forceRefresh: Boolean = false): Result<List<LiveChannel>> =
        withContext(Dispatchers.IO) {
            // An M3U file the user imported lives inside the app; nothing to download.
            if (source.url.startsWith(FILE_PREFIX)) {
                return@withContext try {
                    val channels = M3uParser.parse(File(source.url.removePrefix(FILE_PREFIX)).readText())
                    if (channels.isEmpty()) Result.failure(IOException("No channels in this file")) else Result.success(channels)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            if (!forceRefresh) synchronized(memory) { memory[source.cacheKey] }?.let { return@withContext Result.success(it) }

            val file = File(cacheDir, "${source.cacheKey}.m3u")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
            if (!forceRefresh && fresh) {
                parseFile(file)?.let { return@withContext Result.success(remember(source, it)) }
            }

            try {
                val request = Request.Builder().url(source.url).header("Accept", "*/*").build()
                val channels: List<LiveChannel> = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
                    val body = response.body ?: throw IOException("Empty answer")
                    // Look at the first bytes only: a direct stream link (video, audio, ...) must not be downloaded.
                    val peek = body.source().peek()
                    peek.request(1024)
                    val head = peek.readUtf8(minOf(1024L, peek.buffer.size)).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    if (!head.startsWith("#EXTM3U")) {
                        listOf(singleStream(source.url))
                    } else {
                        if (body.contentLength() > MAX_PLAYLIST_BYTES) throw IOException("Playlist is too large")
                        val text = body.string()
                        if (text.length > MAX_PLAYLIST_BYTES) throw IOException("Playlist is too large")
                        if (isStreamManifest(text)) {
                            // One HLS stream (not a list of channels): play the link itself.
                            listOf(singleStream(source.url))
                        } else {
                            val parsed = M3uParser.parse(text)
                            if (parsed.isEmpty()) throw IOException("No channels in this playlist")
                            val tmp = File(cacheDir, "${source.cacheKey}.tmp")
                            tmp.writeText(text)
                            if (!tmp.renameTo(file)) {
                                file.writeText(text)
                                tmp.delete()
                            }
                            parsed
                        }
                    }
                }
                Result.success(remember(source, channels))
            } catch (e: Exception) {
                Log.w(TAG, "Playlist ${source.cacheKey} failed: ${e.message}")
                // A stale copy beats an error screen.
                val stale = if (file.exists()) parseFile(file) else null
                if (stale != null) Result.success(remember(source, stale)) else Result.failure(e)
            }
        }

    /** A link that is a stream rather than a list: shown as a single channel, like "Open network stream" in a player. */
    private fun singleStream(url: String) = LiveChannel(
        id = "link_${shortHash(url)}",
        name = playlistLabel(url),
        streamUrl = url
    )

    /** HLS stream manifests start with #EXTM3U too, but describe one stream, not many channels. */
    private fun isStreamManifest(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-MEDIA-SEQUENCE")

    /**
     * Saves an M3U file the user picked into the app's private storage and adds it to the playlists. Throws an
     * [IOException] with a message fit to show when the file isn't a usable playlist.
     */
    fun importFile(displayName: String, input: java.io.InputStream): SavedPlaylist {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_PLAYLIST_BYTES) throw IOException("That file is too large")
            out.write(buffer, 0, n)
        }
        val text = String(out.toByteArray(), Charsets.UTF_8)
        if (!text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) throw IOException("That file isn't an M3U playlist")
        if (M3uParser.parse(text).isEmpty()) throw IOException("No channels found in that file")
        val dir = File(app.filesDir, "playlists").apply { mkdirs() }
        val name = displayName.substringBeforeLast('.').ifBlank { "My playlist" }
        val file = File(dir, "${shortHash(name)}.m3u")
        file.writeText(text)
        val playlist = SavedPlaylist(FILE_PREFIX + file.absolutePath, name)
        addPlaylist(playlist)
        return playlist
    }

    private fun remember(source: LiveSource, list: List<LiveChannel>): List<LiveChannel> {
        synchronized(memory) { memory[source.cacheKey] = list }
        return list
    }

    private fun parseFile(file: File): List<LiveChannel>? = try {
        M3uParser.parse(file.readText()).takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "Cached playlist ${file.name} unreadable: ${e.message}")
        null
    }

    // ── The user's playlists ────────────────────────────────────────────────

    fun playlists(): List<SavedPlaylist> = try {
        val array = JSONArray(prefs.getString(KEY_PLAYLISTS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null else SavedPlaylist(url, o.optString("name").ifBlank { playlistLabel(url) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun addPlaylist(playlist: SavedPlaylist): List<SavedPlaylist> {
        val updated = (playlists().filter { it.url != playlist.url } + playlist).takeLast(MAX_PLAYLISTS)
        writePlaylists(updated)
        return updated
    }

    fun removePlaylist(url: String): List<SavedPlaylist> {
        val updated = playlists().filter { it.url != url }
        writePlaylists(updated)
        File(cacheDir, "${LiveSource(url).cacheKey}.m3u").delete()
        if (url.startsWith(FILE_PREFIX)) File(url.removePrefix(FILE_PREFIX)).delete()
        synchronized(memory) { memory.remove(LiveSource(url).cacheKey) }
        if (selectedUrl() == url) setSelectedUrl(null)
        return updated
    }

    fun selectedUrl(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelectedUrl(url: String?) {
        prefs.edit().apply { if (url == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, url) }.apply()
    }

    private fun writePlaylists(list: List<SavedPlaylist>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("url", it.url).put("name", it.name)) }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
        LiveTvStore.set(list.isNotEmpty())
    }

    // ── Favourites & recents ────────────────────────────────────────────────

    fun favorites(): List<LiveChannel> = readList(KEY_FAVORITES)

    fun recents(): List<LiveChannel> = readList(KEY_RECENTS)

    fun setFavorite(channel: LiveChannel, favorite: Boolean): List<LiveChannel> {
        val list = favorites().filter { it.id != channel.id }
        val updated = if (favorite) listOf(channel) + list else list
        writeList(KEY_FAVORITES, updated.take(MAX_FAVORITES))
        return updated.take(MAX_FAVORITES)
    }

    fun addRecent(channel: LiveChannel): List<LiveChannel> {
        val updated = (listOf(channel) + recents().filter { it.id != channel.id }).take(MAX_RECENTS)
        writeList(KEY_RECENTS, updated)
        return updated
    }

    fun clearRecents() = writeList(KEY_RECENTS, emptyList())

    private fun readList(key: String): List<LiveChannel> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeList(key: String, list: List<LiveChannel>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun toJson(c: LiveChannel) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("url", c.streamUrl)
        put("logo", c.logoUrl ?: JSONObject.NULL)
        put("group", c.group)
        put("country", c.countryCode ?: JSONObject.NULL)
        put("q", c.qualityHeight)
        put("geo", c.isGeoBlocked)
        put("n247", c.isNot247)
        put("ref", c.referrer ?: JSONObject.NULL)
        put("ua", c.userAgent ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject): LiveChannel? {
        val id = o.optString("id")
        val url = o.optString("url")
        if (id.isBlank() || url.isBlank()) return null
        return LiveChannel(
            id = id,
            name = o.optString("name", "Channel"),
            streamUrl = url,
            logoUrl = o.optString("logo").takeIf { it.isNotBlank() && it != "null" },
            group = o.optString("group"),
            countryCode = o.optString("country").takeIf { it.isNotBlank() && it != "null" },
            qualityHeight = o.optInt("q"),
            isGeoBlocked = o.optBoolean("geo"),
            isNot247 = o.optBoolean("n247"),
            referrer = o.optString("ref").takeIf { it.isNotBlank() && it != "null" },
            userAgent = o.optString("ua").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private companion object {
        const val TAG = "LiveTvRepo"
        const val FILE_PREFIX = "file:"
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
        const val MEMORY_LISTS = 6
        const val MAX_PLAYLISTS = 30
        const val MAX_PLAYLIST_BYTES = 60L * 1024 * 1024
        const val MAX_FAVORITES = 200
        const val MAX_RECENTS = 20
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_SELECTED = "selected_playlist"
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
    }
}
