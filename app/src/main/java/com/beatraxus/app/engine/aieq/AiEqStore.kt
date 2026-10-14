package com.beatraxus.app.engine.aieq

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Process-wide cache of [AiEqProfile]s, persisted to one small JSON file in the app's private storage.
 * Independent of Room on purpose: no foreign keys, no migrations, nothing that can reject a save.
 * Thread-safe; [get] is cheap enough to call from the audio engine when a song starts.
 */
class AiEqStore private constructor(private val file: File) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, AiEqProfile>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiEqProfile>?): Boolean = size > MAX_ENTRIES
    }
    @Volatile private var dirty = false
    @Volatile private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let { map[it.songId] = it }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AI EQ cache unreadable, starting empty: ${t.message}")
                map.clear()
            }
            loaded = true
        }
    }

    /** Cached profile for [songId] when it was made by the current engine version, else null. */
    fun get(songId: String): AiEqProfile? {
        ensureLoaded()
        return synchronized(lock) { map[songId]?.takeIf { it.version == AiEqKnowledge.ENGINE_VERSION } }
    }

    fun put(profile: AiEqProfile) {
        ensureLoaded()
        synchronized(lock) { map[profile.songId] = profile }
        dirty = true
    }

    fun snapshot(): Map<String, AiEqProfile> {
        ensureLoaded()
        return synchronized(lock) { LinkedHashMap(map) }
    }

    fun clear() {
        ensureLoaded()
        synchronized(lock) { map.clear() }
        dirty = true
        flush()
    }

    /** Writes the cache to disk if anything changed. Safe to call often; atomic via temp file + rename. */
    fun flush() {
        if (!dirty) return
        val json = synchronized(lock) {
            dirty = false
            JSONArray().also { arr -> map.values.forEach { arr.put(toJson(it)) } }.toString()
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
        } catch (t: Throwable) {
            dirty = true
            Log.w(TAG, "AI EQ cache write failed: ${t.message}")
        }
    }

    private fun toJson(p: AiEqProfile) = JSONObject().apply {
        put("id", p.songId); put("genre", p.genre); put("mood", p.mood); put("lang", p.language)
        put("gains", JSONArray().also { a -> p.gains.forEach { a.put(it.toDouble()) } })
        put("conf", p.confidence.toDouble()); put("src", p.source.name); put("ver", p.version); put("at", p.createdAt)
    }

    private fun fromJson(o: JSONObject): AiEqProfile? = try {
        val g = o.getJSONArray("gains")
        if (g.length() != AiEqKnowledge.BAND_COUNT) null else AiEqProfile(
            songId = o.getString("id"),
            genre = o.optString("genre", "Pop"),
            mood = o.optString("mood", "Happy"),
            language = o.optString("lang", "English"),
            gains = FloatArray(AiEqKnowledge.BAND_COUNT) { g.getDouble(it).toFloat() },
            confidence = o.optDouble("conf", 0.5).toFloat(),
            source = runCatching { AiEqProfile.Source.valueOf(o.optString("src", "METADATA")) }.getOrDefault(AiEqProfile.Source.METADATA),
            version = o.optInt("ver", 0),
            createdAt = o.optLong("at", 0L)
        )
    } catch (t: Throwable) { null }

    companion object {
        private const val TAG = "AiEqStore"
        private const val MAX_ENTRIES = 5000

        @Volatile private var instance: AiEqStore? = null

        fun getInstance(context: Context): AiEqStore =
            instance ?: synchronized(this) {
                instance ?: AiEqStore(File(context.applicationContext.filesDir, "ai_eq/profiles_v1.json")).also { instance = it }
            }

        /** Test hook: a store backed by an arbitrary file. */
        fun forFile(file: File): AiEqStore = AiEqStore(file)
    }
}
