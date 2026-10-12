package com.beatraxus.app.features

import android.media.MediaDataSource
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs that are still arriving from the controller (Nearby, own Wi-Fi or internet).
 *
 * Like a cloud song on Google Drive, a Play Together song can start playing while it is still being
 * downloaded: the receiver registers the growing file here, and [PtGrowingDataSource] lets the decoder
 * read it (waiting for bytes that have not arrived yet) instead of waiting for the whole file.
 *
 * The registry is keyed by the FINAL path of the song (the one the song's uri points to), even when
 * the bytes are written to a ".part" file first.
 */
object PtPartialFiles {
    const val GROWING = 0
    const val COMPLETE = 1
    const val FAILED = -1

    class Entry(
        /** Where the song ends up (and what the Song's uri points to). */
        val finalFile: File,
        /** Full size in bytes, from the controller's header. */
        val total: Long
    ) {
        /** The file the bytes are written to right now (a ".part" file or [finalFile]). */
        @Volatile var file: File = finalFile
        @Volatile var written: Long = 0L
        @Volatile var state: Int = GROWING
        @Volatile var lastWriteAt: Long = SystemClock.elapsedRealtime()
        val startedAt: Long = SystemClock.elapsedRealtime()

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

        /** Call after [n] more bytes were written to [file]. */
        fun advance(n: Int) {
            written += n
            lastWriteAt = SystemClock.elapsedRealtime()
        }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun begin(finalFile: File, total: Long, writingTo: File = finalFile): Entry {
        val e = Entry(finalFile, total)
        e.file = writingTo
        entries[finalFile.absolutePath] = e
        return e
    }

    fun get(finalFile: File): Entry? = entries[finalFile.absolutePath]

    fun get(path: String?): Entry? = if (path.isNullOrEmpty()) null else entries[path]

    /** All bytes are in [Entry.finalFile]. */
    fun complete(finalFile: File) {
        entries[finalFile.absolutePath]?.let {
            it.file = it.finalFile
            it.written = it.total.coerceAtLeast(it.written)
            it.state = COMPLETE
        }
    }

    /** The transfer broke: readers waiting on this file give up with an error. */
    fun fail(finalFile: File) {
        entries[finalFile.absolutePath]?.state = FAILED
    }

    fun remove(finalFile: File) {
        entries.remove(finalFile.absolutePath)?.let { if (it.state == GROWING) it.state = FAILED }
    }

    fun clear() {
        entries.values.forEach { if (it.state == GROWING) it.state = FAILED }
        entries.clear()
    }
}

/**
 * A [MediaDataSource] over a file that is still being written. Reads wait for the bytes to arrive;
 * a read fails only when the transfer failed or nothing arrived for [STALL_MS].
 * (Same idea as the streaming data sources of the cloud songs.)
 */
internal class PtGrowingDataSource(private val entry: PtPartialFiles.Entry) : MediaDataSource() {
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    override fun getSize(): Long = if (entry.total > 0) entry.total else -1L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val total = entry.total
        if (total > 0 && position >= total) return -1
        while (true) {
            val state = entry.state
            if (state == PtPartialFiles.FAILED) throw IOException("Play Together transfer failed")
            val limit = if (state == PtPartialFiles.COMPLETE) entry.finalFile.length() else entry.written
            if (position < limit) {
                val want = minOf(size.toLong(), limit - position).toInt()
                val n = readFile(position, buffer, offset, want)
                if (n > 0) return n
            } else if (state == PtPartialFiles.COMPLETE) {
                return -1
            }
            if (SystemClock.elapsedRealtime() - entry.lastWriteAt > STALL_MS) {
                throw IOException("Play Together transfer stalled")
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted")
            }
        }
    }

    private fun readFile(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        // the file can be renamed from "x.part" to "x" while we read: reopen when that happens
        for (attempt in 0..1) {
            try {
                if (raf == null) {
                    // between the rename of "x.part" to "x" and the state change only the final name exists
                    val f = entry.file.takeIf { it.exists() } ?: entry.finalFile
                    raf = RandomAccessFile(f, "r")
                    rafPath = f.absolutePath
                }
                val r = raf ?: return -1
                r.seek(position)
                return r.read(buffer, offset, length)
            } catch (e: java.io.FileNotFoundException) {
                closeQuietly()
                if (attempt == 1) throw e
            }
        }
        return -1
    }

    private fun closeQuietly() {
        try { raf?.close() } catch (_: Exception) { }
        raf = null; rafPath = null
    }

    @Synchronized
    override fun close() = closeQuietly()

    private companion object {
        const val POLL_MS = 20L
        const val STALL_MS = 20_000L
    }
}

/**
 * Pure rules: may a song that is still downloading start playing now?
 *
 * It may when the download will stay ahead of the playback, so the song plays to the end without
 * stopping (the same trade a cloud player makes): enough bytes for the first seconds, and either a
 * download faster than the song's own byte rate, or a head start big enough to cover the difference.
 */
internal object PtStreamPlan {
    /** Never start with less than this (container headers, first frames). */
    const val MIN_PREFIX_BYTES = 256L * 1024
    /** ...or less than this much audio. */
    const val MIN_PREFIX_MS = 4_000L
    /** Measure the speed for this long before trusting it. */
    const val MIN_SAMPLE_MS = 1_200L
    /** Only count 80 % of the measured speed (Wi-Fi / Bluetooth speed moves around). */
    const val SPEED_SAFETY = 0.8

    private val STREAMABLE = setOf("mp3", "flac", "ogg", "oga", "opus", "aac")

    /**
     * Formats whose decoder can start from the front of the file. m4a / mp4 / alac / wav and the
     * like may keep their index at the end or are decoded by FFmpeg, so those wait for the whole file.
     */
    fun streamable(ext: String): Boolean = ext.lowercase() in STREAMABLE

    /**
     * @param written bytes that arrived so far
     * @param total full size of the file
     * @param durationMs length of the song
     * @param elapsedMs how long the download has been running
     * @param startPosMs where the phone has to start (the room's position, 0 for a new song)
     */
    fun shouldStart(written: Long, total: Long, durationMs: Long, elapsedMs: Long, startPosMs: Long = 0L): Boolean {
        if (total <= 0 || durationMs <= 0) return false
        if (written >= total) return true
        val bytesPerMs = total.toDouble() / durationMs
        val startByte = (startPosMs.coerceIn(0L, durationMs) * bytesPerMs).toLong()
        val prefix = maxOf(MIN_PREFIX_BYTES, (MIN_PREFIX_MS * bytesPerMs).toLong())
        val head = written - startByte // data that is ready from where playback starts
        if (head < prefix) return false
        if (elapsedMs < MIN_SAMPLE_MS || written <= 0) return false

        val speed = written.toDouble() / elapsedMs * SPEED_SAFETY // bytes per ms, with a safety margin
        if (speed >= bytesPerMs) return true // downloads faster than it plays: safe from the start
        val remaining = (total - written).toDouble()
        val downloadMs = remaining / speed
        // playing consumes (bytesPerMs - speed) of the head start every ms until the download ends
        return head >= (bytesPerMs - speed) * downloadMs
    }
}
