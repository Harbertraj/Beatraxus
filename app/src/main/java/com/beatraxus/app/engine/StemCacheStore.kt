package com.beatraxus.app.engine

import com.beatraxus.app.model.StemQuality
import com.beatraxus.app.model.StemSpec
import com.beatraxus.app.model.StemSpecs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The "done" marker of a stem folder: `version:engineId:stemCount`, e.g. `3:htdemucs6s:6`.
 * A folder only counts as cached for the engine whose marker it carries.
 */
object StemCacheFormat {
    /**
     * Bump when the stem format or quality changes. 2: stems made before the decoder fix were garbage. 3: stems are
     * kept per quality (Fast / Max) in `v3/<quality>/<song>`, the marker names the engine and the stem count.
     */
    const val VERSION = "3"
    const val DONE_FILENAME = "done"

    /** Folder (inside the stems root) of everything written by this cache version. Other folders are old. */
    const val VERSION_DIR = "v$VERSION"

    data class Marker(val version: String, val engineId: String, val stemCount: Int)

    fun marker(quality: StemQuality): String =
        "$VERSION:${quality.engineId}:${StemSpecs.forQuality(quality).size}"

    /** Null for anything that is not `version:engineId:count` (old "2" markers, empty or damaged files). */
    fun parse(text: String?): Marker? {
        val parts = text?.trim()?.split(':') ?: return null
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        val count = parts[2].toIntOrNull() ?: return null
        return Marker(parts[0], parts[1], count)
    }

    fun matches(text: String?, quality: StemQuality): Boolean {
        val m = parse(text) ?: return false
        return m.version == VERSION && m.engineId == quality.engineId && m.stemCount == StemSpecs.forQuality(quality).size
    }
}

/**
 * The stem cache on disk, with no Android in it (the app passes `cacheDir/stems`).
 *
 * Layout: `root/v3/fast/<song>/{vocals,drums,bass,other}.pcm + done` and `root/v3/max/<song>/{...6 files...} + done`.
 * Fast and Max live side by side, so switching quality never deletes the other form of a song.
 */
class StemCacheStore(val root: File) {

    private fun versionRoot() = File(root, StemCacheFormat.VERSION_DIR)

    private fun qualityRoot(quality: StemQuality) = File(versionRoot(), quality.folderName)

    private fun safeId(songId: String) = songId.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    /** The folder of a song for a quality. Not created unless [create]. */
    fun dirFor(songId: String, quality: StemQuality, create: Boolean = false): File {
        val dir = File(qualityRoot(quality), safeId(songId))
        if (create && !dir.exists()) dir.mkdirs()
        return dir
    }

    fun stemFiles(songId: String, quality: StemQuality): List<File> {
        val dir = dirFor(songId, quality)
        return StemSpecs.forQuality(quality).map { File(dir, it.fileName) }
    }

    /** True only when the marker matches this quality's engine and every stem file exists and has the same size. */
    fun isCached(songId: String, quality: StemQuality): Boolean {
        val dir = dirFor(songId, quality)
        if (!dir.isDirectory) return false
        if (!markerMatches(File(dir, StemCacheFormat.DONE_FILENAME), quality)) return false
        val files = stemFiles(songId, quality)
        val first = files.first().length()
        if (first <= 0L) return false
        return files.all { it.isFile && it.length() == first }
    }

    fun markDone(songId: String, quality: StemQuality) {
        val dir = dirFor(songId, quality, create = true)
        File(dir, StemCacheFormat.DONE_FILENAME).writeText(StemCacheFormat.marker(quality))
    }

    /** Marks a cached song as used now, so the size guard evicts the least recently used songs first. */
    fun touch(songId: String, quality: StemQuality) {
        File(dirFor(songId, quality), StemCacheFormat.DONE_FILENAME).setLastModified(System.currentTimeMillis())
    }

    private fun markerMatches(f: File, quality: StemQuality): Boolean =
        f.isFile && try { StemCacheFormat.matches(f.readText(), quality) } catch (_: Exception) { false }

    /** Deletes the stems of a song: one quality, or both when [quality] is null. */
    fun deleteStems(songId: String, quality: StemQuality? = null) {
        val targets = if (quality == null) StemQuality.entries else listOf(quality)
        for (q in targets) dirFor(songId, q).deleteRecursively()
    }

    /**
     * Deletes everything an older cache layout or version left behind: folders outside `v3`, unknown quality folders,
     * song folders whose marker does not match their quality, and unfinished folders (no marker) older than
     * [orphanAgeMs]. Returns how many folders were removed.
     */
    fun purgeOutdated(orphanAgeMs: Long = 60L * 60L * 1000L, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val children = root.listFiles() ?: return 0
        for (child in children) {
            if (child.name != StemCacheFormat.VERSION_DIR) {
                if (child.deleteRecursively()) removed++
            }
        }
        val vRoot = versionRoot()
        val knownFolders = StemQuality.entries.associateBy { it.folderName }
        vRoot.listFiles()?.forEach { qDir ->
            val quality = knownFolders[qDir.name]
            if (quality == null || !qDir.isDirectory) {
                if (qDir.deleteRecursively()) removed++
                return@forEach
            }
            qDir.listFiles()?.forEach { songDir ->
                val marker = File(songDir, StemCacheFormat.DONE_FILENAME)
                val stale = when {
                    !songDir.isDirectory -> true
                    marker.exists() -> !markerMatches(marker, quality)
                    else -> now - songDir.lastModified() > orphanAgeMs
                }
                if (stale && songDir.deleteRecursively()) removed++
            }
        }
        return removed
    }

    fun totalSizeBytes(): Long = sizeOf(root)

    fun sizeBytes(quality: StemQuality): Long = sizeOf(qualityRoot(quality))

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)

    /** The "Clear stem cache" action: removes every stem of every song, both qualities and old layouts. */
    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Size guard: makes room for [incomingBytes] by deleting the least recently used cached songs until the cache
     * (plus the incoming stems) fits [limitBytes]. The song in [keepSongId] / [keepQuality] is never deleted.
     * Returns the number of songs removed. If one song alone is bigger than the limit it is still allowed.
     */
    fun enforceLimit(limitBytes: Long, incomingBytes: Long, keepSongId: String? = null, keepQuality: StemQuality? = null): Int {
        var total = totalSizeBytes()
        if (total + incomingBytes <= limitBytes) return 0
        val keep = keepSongId?.let { safeId(it) }
        class Entry(val dir: File, val used: Long, val size: Long)
        val entries = ArrayList<Entry>()
        for (q in StemQuality.entries) {
            qualityRoot(q).listFiles()?.forEach { songDir ->
                if (!songDir.isDirectory) return@forEach
                if (keep != null && songDir.name == keep && q == keepQuality) return@forEach
                val marker = File(songDir, StemCacheFormat.DONE_FILENAME)
                val used = if (marker.exists()) marker.lastModified() else songDir.lastModified()
                entries += Entry(songDir, used, sizeOf(songDir))
            }
        }
        entries.sortBy { it.used }
        var removed = 0
        for (e in entries) {
            if (total + incomingBytes <= limitBytes) break
            if (e.dir.deleteRecursively()) {
                total -= e.size
                removed++
            }
        }
        return removed
    }

    fun openReader(songId: String, quality: StemQuality): Reader =
        Reader(dirFor(songId, quality), StemSpecs.forQuality(quality))

    /**
     * Keeps the stem files open and reuses one buffer, so the audio thread does no allocation or file opening per
     * buffer. Stem index i is the i-th stem of the engine's [StemSpec] list. Close it when the song goes away.
     */
    class Reader(private val dir: File, private val specs: List<StemSpec>) : java.io.Closeable {
        private val rafs = arrayOfNulls<RandomAccessFile>(specs.size)
        private var bb: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())

        val stemCount: Int get() = specs.size

        /** Reads [frameCount] stereo frames at [frameOffset] into [out] (zero filled past EOF). False if unreadable. */
        fun read(stemIndex: Int, frameOffset: Long, frameCount: Int, out: FloatArray): Boolean {
            if (stemIndex !in specs.indices || out.size < frameCount * 2) return false
            return try {
                val raf = rafs[stemIndex] ?: RandomAccessFile(File(dir, specs[stemIndex].fileName), "r")
                    .also { rafs[stemIndex] = it }
                val byteOffset = frameOffset * 8
                val size = raf.length()
                if (byteOffset >= size) return false
                val want = (minOf(frameCount * 8L, size - byteOffset).toInt()) and 7.inv()
                if (bb.capacity() < want) bb = ByteBuffer.allocateDirect(want).order(ByteOrder.nativeOrder())
                bb.clear()
                bb.limit(want)
                val ch = raf.channel
                ch.position(byteOffset)
                while (bb.hasRemaining()) if (ch.read(bb) < 0) break
                bb.flip()
                val floats = bb.remaining() / 4
                bb.asFloatBuffer().get(out, 0, floats)
                if (floats < frameCount * 2) out.fill(0f, floats, frameCount * 2)
                true
            } catch (_: Exception) {
                false
            }
        }

        override fun close() {
            for (i in rafs.indices) {
                try { rafs[i]?.close() } catch (_: Throwable) {}
                rafs[i] = null
            }
        }
    }
}
