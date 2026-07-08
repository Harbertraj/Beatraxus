package com.beatflowy.app.drive

import android.content.Context
import com.beatflowy.app.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackLruCache(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "cloud_cache").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("playback_lru_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // LinkedHashMap with accessOrder = true for LRU
    private val lruMap: LinkedHashMap<String, Long> by lazy {
        val json = prefs.getString("lru_map", null)
        if (json != null) {
            val type = object : TypeToken<LinkedHashMap<String, Long>>() {}.type
            try {
                val map: LinkedHashMap<String, Long> = gson.fromJson(json, type)
                // We need to recreate it with accessOrder = true because Gson doesn't preserve it
                val newMap = LinkedHashMap<String, Long>(16, 0.75f, true)
                newMap.putAll(map)
                newMap
            } catch (e: Exception) {
                LinkedHashMap<String, Long>(16, 0.75f, true)
            }
        } else {
            LinkedHashMap<String, Long>(16, 0.75f, true)
        }
    }

    /**
     * Ensures the song is in the 15-song shared LRU cache.
     * @param isPlayback If true, updates access order (counting towards 15). 
     *                   If false (pre-fetch), ensures file exists without updating recency.
     * @param currentlyPlayingId The ID of the song currently being played, which should NEVER be evicted.
     */
    suspend fun getOrCacheFile(song: Song, sourceFile: File, isPlayback: Boolean, currentlyPlayingId: String?): File = withContext(Dispatchers.IO) {
        val extension = sourceFile.extension.takeIf { it.isNotBlank() } ?: "cache"
        val cachedFile = File(cacheDir, "${song.id}.$extension")

        synchronized(lruMap) {
            val fileExists = cachedFile.exists() && cachedFile.length() > 0

            if (isPlayback) {
                // Playback started: move to most-recent
                lruMap[song.id] = System.currentTimeMillis()
            } else if (!lruMap.containsKey(song.id)) {
                // Pre-fetch and NOT in cache: add as least-recent (timestamp 0)
                lruMap[song.id] = 0L
            }

            // Evict if we just added a 16th entry
            if (lruMap.size > 15) {
                evictOldest(currentlyPlayingId)
            }

            if (!fileExists) {
                if (sourceFile.absolutePath != cachedFile.absolutePath) {
                    sourceFile.copyTo(cachedFile, overwrite = true)
                }
            }
            
            persistMap()
        }

        cachedFile
    }

    /**
     * Checks if a song is currently in the LRU cache.
     */
    fun getCachedFile(song: Song): File? = getCachedFileById(song.id)

    fun getCachedFileById(songId: String): File? {
        return synchronized(lruMap) {
            cacheDir.listFiles { _, name -> name.startsWith("$songId.") }
                ?.firstOrNull { it.length() > 0 }
        }
    }

    fun clearCache(excludeId: String? = null) {
        synchronized(lruMap) {
            val toRemove = lruMap.keys.filter { it != excludeId }
            toRemove.forEach { lruMap.remove(it) }
            persistMap()

            if (excludeId == null) {
                // Optimized full wipe: rename and delete is often faster than listFiles if dir is large
                val trash = File(cacheDir.parent, "cloud_cache_trash_${System.currentTimeMillis()}")
                if (cacheDir.renameTo(trash)) {
                    // Start deletion in background if possible, but here we just deleteRecursively
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
    }

    private fun evictOldest(currentlyPlayingId: String?) {
        // LinkedHashMap with accessOrder=true: first entry is oldest accessed
        val iterator = lruMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val oldestId = entry.key
            
            // Safety check: Don't evict the song that is currently playing!
            if (oldestId == currentlyPlayingId) {
                continue
            }
            
            iterator.remove()
            // Find and delete the file associated with this ID
            cacheDir.listFiles { _, name -> name.startsWith("$oldestId.") }?.forEach { it.delete() }
            return // Successfully evicted one non-playing song
        }
    }

    private fun persistMap() {
        prefs.edit().putString("lru_map", gson.toJson(lruMap)).apply()
    }
}
