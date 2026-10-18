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
 * Loads iptv-org playlists and keeps the user's favourite / recently watched channels.
 *
 * Playlists are cached on disk for [CACHE_TTL_MS]; when the network is down a stale copy is still used, so Live TV
 * opens instantly and keeps working offline for the channel list (the streams themselves obviously need a network).
 */
class LiveTvRepository(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("livetv", Context.MODE_PRIVATE)
    private val cacheDir = File(app.cacheDir, "livetv").apply { mkdirs() }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val memory = object : LinkedHashMap<String, List<LiveChannel>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LiveChannel>>?): Boolean = size > MEMORY_LISTS
    }

    suspend fun load(source: LiveSource, forceRefresh: Boolean = false): Result<List<LiveChannel>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) synchronized(memory) { memory[source.cacheKey] }?.let { return@withContext Result.success(it) }

            val file = File(cacheDir, "${source.cacheKey}.m3u")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
            if (!forceRefresh && fresh) {
                parseFile(file)?.let { return@withContext Result.success(remember(source, it)) }
            }

            try {
                val request = Request.Builder().url(source.url).header("Accept", "*/*").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (!body.trimStart().startsWith("#EXTM3U")) throw IOException("Not a playlist")
                    val tmp = File(cacheDir, "${source.cacheKey}.tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(file)) {
                        file.writeText(body)
                        tmp.delete()
                    }
                    Result.success(remember(source, M3uParser.parse(body)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Playlist ${source.url} failed: ${e.message}")
                // A stale copy beats an error screen.
                val stale = if (file.exists()) parseFile(file) else null
                if (stale != null) Result.success(remember(source, stale)) else Result.failure(e)
            }
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
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
        const val MEMORY_LISTS = 6
        const val MAX_FAVORITES = 200
        const val MAX_RECENTS = 20
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
    }
}
