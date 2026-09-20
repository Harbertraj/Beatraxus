package com.beatraxus.app.subtitles.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SubtitleCache(
    private val context: Context,
    private val maxCacheSizeBytes: Long = 100 * 1024 * 1024L // 100 MB
) {
    private val gson = Gson()
    private val baseCacheDir: File
        get() = File(context.filesDir, "subtitles").apply { if (!exists()) mkdirs() }

    init {
        cleanupTempFiles()
    }

    fun cleanupTempFiles() {
        try {
            baseCacheDir.walkTopDown().forEach { file ->
                if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith("sub_dl_"))) {
                    file.delete()
                }
            }
            File(context.cacheDir, "zip_temp").deleteRecursively()
        } catch (_: Exception) {
        }
    }

    suspend fun isCached(mediaKey: String, subtitleId: String): Boolean = withContext(Dispatchers.IO) {
        val list = getCachedSubtitles(mediaKey)
        val entry = list.firstOrNull { it.subtitleId == subtitleId } ?: return@withContext false
        File(entry.localPath).exists()
    }

    suspend fun getCachedSubtitles(mediaKey: String): List<CachedSubtitle> = withContext(Dispatchers.IO) {
        val mediaDir = File(baseCacheDir, mediaKey)
        val indexFile = File(mediaDir, "index.json")
        if (!indexFile.exists()) return@withContext emptyList()

        try {
            val json = indexFile.readText(Charsets.UTF_8)
            val index = gson.fromJson(json, MediaCacheIndex::class.java)
            index?.subtitles ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getCachedSubtitle(mediaKey: String, language: String): CachedSubtitle? = withContext(Dispatchers.IO) {
        getCachedSubtitles(mediaKey).firstOrNull { it.language.equals(language, ignoreCase = true) }
    }

    suspend fun saveSubtitle(
        mediaKey: String,
        cachedSubtitle: CachedSubtitle,
        fileContent: String
    ): File = withContext(Dispatchers.IO) {
        val mediaDir = File(baseCacheDir, mediaKey).apply { if (!exists()) mkdirs() }
        val srtFileName = "${cachedSubtitle.language}_${cachedSubtitle.subtitleId}.srt"
        val srtFile = File(mediaDir, srtFileName)

        atomicWrite(srtFile, fileContent.toByteArray(Charsets.UTF_8))

        val currentList = getCachedSubtitles(mediaKey).filter { it.subtitleId != cachedSubtitle.subtitleId }
        val updatedSubtitle = cachedSubtitle.copy(
            localPath = srtFile.absolutePath,
            lastUsed = System.currentTimeMillis()
        )
        val updatedList = currentList + updatedSubtitle
        saveIndex(mediaDir, mediaKey, updatedList)

        pruneCacheIfNeeded()

        srtFile
    }

    suspend fun updateLastUsed(mediaKey: String, subtitleId: String) = withContext(Dispatchers.IO) {
        val mediaDir = File(baseCacheDir, mediaKey)
        val list = getCachedSubtitles(mediaKey)
        val updated = list.map {
            if (it.subtitleId == subtitleId) it.copy(lastUsed = System.currentTimeMillis()) else it
        }
        saveIndex(mediaDir, mediaKey, updated)
    }

    suspend fun updateDelay(mediaKey: String, subtitleId: String, delayMs: Long) = withContext(Dispatchers.IO) {
        val mediaDir = File(baseCacheDir, mediaKey)
        val list = getCachedSubtitles(mediaKey)
        val updated = list.map {
            if (it.subtitleId == subtitleId) it.copy(delayMs = delayMs) else it
        }
        saveIndex(mediaDir, mediaKey, updated)
    }

    suspend fun deleteSubtitle(mediaKey: String, subtitleId: String): Boolean = withContext(Dispatchers.IO) {
        val mediaDir = File(baseCacheDir, mediaKey)
        val list = getCachedSubtitles(mediaKey)
        val entry = list.firstOrNull { it.subtitleId == subtitleId }

        if (entry != null) {
            val file = File(entry.localPath)
            if (file.exists()) file.delete()
            val remaining = list.filter { it.subtitleId != subtitleId }
            saveIndex(mediaDir, mediaKey, remaining)
            true
        } else {
            false
        }
    }

    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        if (baseCacheDir.exists()) {
            baseCacheDir.deleteRecursively()
            baseCacheDir.mkdirs()
            true
        } else {
            false
        }
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        calculateDirSize(baseCacheDir)
    }

    private fun saveIndex(mediaDir: File, mediaKey: String, subtitles: List<CachedSubtitle>) {
        val indexFile = File(mediaDir, "index.json")
        val index = MediaCacheIndex(mediaKey, subtitles)
        val json = gson.toJson(index)
        atomicWrite(indexFile, json.toByteArray(Charsets.UTF_8))
    }

    private fun atomicWrite(targetFile: File, bytes: ByteArray) {
        val parent = targetFile.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()

        val tempFile = File(parent, "${targetFile.name}.tmp")
        FileOutputStream(tempFile).use { it.write(bytes) }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun pruneCacheIfNeeded() {
        val currentSize = calculateDirSize(baseCacheDir)
        if (currentSize <= maxCacheSizeBytes) return

        val targetSizeBytes = (maxCacheSizeBytes * 0.8).toLong()

        val allSubtitles = mutableListOf<Pair<String, CachedSubtitle>>()
        val mediaDirs = baseCacheDir.listFiles() ?: return

        for (dir in mediaDirs) {
            if (dir.isDirectory) {
                val mediaKey = dir.name
                val indexFile = File(dir, "index.json")
                if (indexFile.exists()) {
                    try {
                        val json = indexFile.readText(Charsets.UTF_8)
                        val index = gson.fromJson(json, MediaCacheIndex::class.java)
                        index?.subtitles?.forEach { sub ->
                            allSubtitles.add(Pair(mediaKey, sub))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val sortedByLru = allSubtitles.sortedBy { it.second.lastUsed }

        var size = currentSize
        for ((mediaKey, sub) in sortedByLru) {
            if (size <= targetSizeBytes) break
            val srtFile = File(sub.localPath)
            val subSize = if (srtFile.exists()) srtFile.length() else 0L

            val mediaDir = File(baseCacheDir, mediaKey)
            if (srtFile.exists()) srtFile.delete()

            val currentList = getCachedSubtitlesSync(mediaDir)
            val updated = currentList.filter { it.subtitleId != sub.subtitleId }
            saveIndex(mediaDir, mediaKey, updated)

            size -= subSize
        }
    }

    private fun getCachedSubtitlesSync(mediaDir: File): List<CachedSubtitle> {
        val indexFile = File(mediaDir, "index.json")
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = indexFile.readText(Charsets.UTF_8)
            val index = gson.fromJson(json, MediaCacheIndex::class.java)
            index?.subtitles ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }
}
