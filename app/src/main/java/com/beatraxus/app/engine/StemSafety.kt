package com.beatraxus.app.engine

import com.beatraxus.app.model.StemQuality
import java.io.File

/**
 * Remembers that a separation is running, so the next process start can tell that the previous one was killed in the
 * middle of it (Android's low-memory killer leaves no exception in the log). Plain file, no Android.
 */
class StemRunMarker(private val file: File) {

    data class Unfinished(val quality: StemQuality, val songId: String, val startedAtMs: Long)

    /** Writes the marker and returns this run's token; pass it to [end]. */
    fun begin(songId: String, quality: StemQuality, nowMs: Long = System.currentTimeMillis()): String {
        val token = "${quality.name}|$nowMs|$songId"
        try {
            file.parentFile?.mkdirs()
            file.writeText(token)
        } catch (_: Exception) {}
        return token
    }

    /** Removes the marker, but only if it is still this run's: a newer run must not lose its own marker. */
    fun end(token: String) {
        try {
            if (file.isFile && file.readText() == token) file.delete()
        } catch (_: Exception) {}
    }

    /** The run a previous process left unfinished, or null. Clears the marker so it is reported once. */
    fun consumeUnfinished(): Unfinished? {
        if (!file.isFile) return null
        val text = try { file.readText() } catch (_: Exception) { null }
        try { file.delete() } catch (_: Exception) {}
        val parts = text?.split('|', limit = 3) ?: return null
        if (parts.size != 3) return null
        val started = parts[1].toLongOrNull() ?: return null
        val quality = StemQuality.entries.firstOrNull { it.name == parts[0] } ?: return null
        return Unfinished(quality, parts[2], started)
    }
}

/**
 * Decides what to do when the system runs short of memory in the middle of a separation. Waiting a little often
 * lets Android reclaim cached apps; stopping cleanly with a message beats being killed with no message at all.
 */
object StemMemoryGate {
    enum class Verdict { OK, WAIT, ABORT }

    /** Below this much available RAM the system is about to start killing apps, whatever its own flag says. */
    const val MIN_RUNNING_AVAIL_BYTES = 200L * 1024 * 1024
    const val MAX_WAITS = 3
    const val WAIT_MS = 2_000L

    fun isLow(systemLowFlag: Boolean, availBytes: Long): Boolean =
        systemLowFlag || availBytes < MIN_RUNNING_AVAIL_BYTES

    fun decide(systemLowFlag: Boolean, availBytes: Long, waitsSoFar: Int, maxWaits: Int = MAX_WAITS): Verdict = when {
        !isLow(systemLowFlag, availBytes) -> Verdict.OK
        waitsSoFar < maxWaits -> Verdict.WAIT
        else -> Verdict.ABORT
    }
}

/** Estimates and thresholds used by the manager that are plain arithmetic. */
object StemBudget {
    /** Bytes the 44.1 kHz stereo float temp decode file needs. */
    fun decodeBytes(totalFrames: Long): Long = totalFrames * 8L

    /** Extra storage a run needs while it works: the decoded file plus the stems. */
    fun runBytes(totalFrames: Long, stemCount: Int): Long = totalFrames * 8L * (1 + stemCount)

    /** Remaining time from the chunks done so far; null until two chunks give a usable average. */
    fun etaMs(elapsedMs: Long, chunksDone: Int, totalChunks: Int): Long? =
        if (chunksDone < 2 || totalChunks < chunksDone) null else elapsedMs * (totalChunks - chunksDone) / chunksDone
}
