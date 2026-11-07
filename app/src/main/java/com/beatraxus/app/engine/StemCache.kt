package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import com.beatraxus.app.model.StemQuality
import java.io.File

/**
 * Android face of the stem cache ([StemCacheStore] holds the logic). Stems live in `cacheDir/stems/v3/<fast|max>/<song>`
 * so the system may reclaim them under storage pressure; the app also keeps them under [SIZE_LIMIT_BYTES].
 */
object StemCache {
    private const val TAG = "StemCache"

    /**
     * Total stem cache size the app keeps. A stem is 8 bytes per frame (44.1 kHz stereo float), about 21 MB per
     * minute of music, so a minute costs about 85 MB as 4 stems and 127 MB as 6 stems (arithmetic, not measured).
     */
    const val SIZE_LIMIT_BYTES = 3L * 1024 * 1024 * 1024

    fun store(context: Context): StemCacheStore {
        val root = File(context.cacheDir, "stems")
        if (!root.exists()) root.mkdirs()
        return StemCacheStore(root)
    }

    /** Bytes the stems of [totalFrames] frames take for [stemCount] stems. */
    fun stemBytes(totalFrames: Long, stemCount: Int): Long = totalFrames * 8L * stemCount

    fun getStemsDir(context: Context, songId: String, quality: StemQuality): File =
        store(context).dirFor(songId, quality, create = true)

    fun isStemsCached(context: Context, songId: String, quality: StemQuality): Boolean =
        store(context).isCached(songId, quality)

    fun markStemsDone(context: Context, songId: String, quality: StemQuality) {
        try {
            store(context).markDone(songId, quality)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write done marker for $songId (${quality.name}): ${e.message}")
        }
    }

    fun stemFiles(context: Context, songId: String, quality: StemQuality): List<File> =
        store(context).stemFiles(songId, quality)

    /** Deletes the stems of a song for one quality, or for both when [quality] is null. */
    fun deleteStems(context: Context, songId: String, quality: StemQuality? = null) {
        try {
            store(context).deleteStems(songId, quality)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete stems for $songId: ${e.message}")
        }
    }

    /** Removes caches written by an older layout or version (their marker or folder does not match). */
    fun purgeOutdated(context: Context) {
        try {
            val n = store(context).purgeOutdated()
            if (n > 0) Log.i(TAG, "purgeOutdated removed $n outdated stem folders")
        } catch (e: Exception) {
            Log.e(TAG, "purgeOutdated failed: ${e.message}")
        }
    }

    fun totalSizeBytes(context: Context): Long =
        try { store(context).totalSizeBytes() } catch (_: Exception) { 0L }

    /** "Clear stem cache": every stem of every song, Fast and Max. */
    fun clearAll(context: Context) {
        try {
            store(context).clearAll()
            Log.i(TAG, "Stem cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "clearAll failed: ${e.message}")
        }
    }

    /** Makes room for [incomingBytes] under [SIZE_LIMIT_BYTES]; the song being written is never evicted. */
    fun enforceLimit(context: Context, incomingBytes: Long, keepSongId: String, keepQuality: StemQuality) {
        try {
            val n = store(context).enforceLimit(SIZE_LIMIT_BYTES, incomingBytes, keepSongId, keepQuality)
            if (n > 0) Log.i(TAG, "Size guard removed $n least recently used stem sets")
        } catch (e: Exception) {
            Log.e(TAG, "enforceLimit failed: ${e.message}")
        }
    }

    fun openReader(context: Context, songId: String, quality: StemQuality): StemCacheStore.Reader =
        store(context).openReader(songId, quality)
}
