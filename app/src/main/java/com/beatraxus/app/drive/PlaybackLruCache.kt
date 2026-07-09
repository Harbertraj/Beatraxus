package com.beatraxus.app.drive

import android.content.Context
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackLruCache(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "cloud_cache").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("playback_lru_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val lruMaps: MutableMap<SongSource, LinkedHashMap<String, Long>> = mutableMapOf()

    private fun mapFor(source: SongSource): LinkedHashMap<String, Long> {
        return lruMaps.getOrPut(source) { loadMap(source) }
    }

    private fun loadMap(source: SongSource): LinkedHashMap<String, Long> {
        val json = prefs.getString("lru_map_${source.name}", null)
        return if (json != null) {
            val type = object : TypeToken<LinkedHashMap<String, Long>>() {}.type
            try {
                val map: LinkedHashMap<String, Long> = gson.fromJson(json, type)
                LinkedHashMap<String, Long>(16, 0.75f, true).apply { putAll(map) }
            } catch (e: Exception) {
                LinkedHashMap(16, 0.75f, true)
            }
        } else {
            LinkedHashMap(16, 0.75f, true)
        }
    }

    /**
     * Ensures the song is in the 15-song per-source LRU cache.
     * @param isPlayback If true, updates access order (counting towards 15).
     *                   If false (pre-fetch), ensures file exists without updating recency.
     * @param currentlyPlayingId The ID of the song currently being played, which should NEVER be evicted.
     */
    suspend fun getOrCacheFile(song: Song, sourceFile: File, isPlayback: Boolean, currentlyPlayingId: String?): File = withContext(Dispatchers.IO) {
        val map = mapFor(song.source)
        val extension = sourceFile.extension.takeIf { it.isNotBlank() } ?: "cache"
        val cachedFile = File(cacheDir, "${song.id}.$extension")
        var needsCopy = false

        synchronized(map) {
            val fileExists = cachedFile.exists() && cachedFile.length() > 0
            if (isPlayback) {
                map[song.id] = System.currentTimeMillis()
            } else if (!map.containsKey(song.id)) {
                map[song.id] = 0L
            }
            if (map.size > 15) {
                evictOldest(map, currentlyPlayingId)
            }
            needsCopy = !fileExists && sourceFile.absolutePath != cachedFile.absolutePath
            persistMap(song.source, map)
        }

        if (needsCopy) sourceFile.copyTo(cachedFile, overwrite = true)
        cachedFile
    }

    /**
     * Checks if a song is currently in the LRU cache.
     */
    fun getCachedFile(song: Song): File? = getCachedFileById(song.id)

    fun getCachedFileById(songId: String): File? {
        // Source-agnostic lookup: filenames are unique by ID
        return cacheDir.listFiles { _, name -> name.startsWith("$songId.") }
            ?.firstOrNull { it.length() > 0 }
    }

    fun clearCache(excludeId: String? = null) {
        SongSource.values().forEach { source ->
            val map = mapFor(source)
            synchronized(map) {
                val toRemove = map.keys.filter { it != excludeId }
                toRemove.forEach { map.remove(it) }
                persistMap(source, map)
            }
        }

        if (excludeId == null) {
            val trash = File(cacheDir.parent, "cloud_cache_trash_${System.currentTimeMillis()}")
            if (cacheDir.renameTo(trash)) {
                trash.deleteRecursively()
            } else {
                cacheDir.deleteRecursively()
            }
            cacheDir.mkdirs()
        } else {
            cacheDir.listFiles()?.forEach { file ->
                if (!file.name.startsWith("$excludeId.")) {
                    file.delete()
                }
            }
        }
    }

    private fun evictOldest(map: LinkedHashMap<String, Long>, currentlyPlayingId: String?) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == currentlyPlayingId) continue
            iterator.remove()
            cacheDir.listFiles { _, name -> name.startsWith("${entry.key}.") }?.forEach { it.delete() }
            return
        }
    }

    private fun persistMap(source: SongSource, map: LinkedHashMap<String, Long>) {
        prefs.edit().putString("lru_map_${source.name}", gson.toJson(map)).apply()
    }
}
