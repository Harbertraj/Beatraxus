package com.beatraxus.app.engine

import com.beatraxus.app.model.StemSpec

/**
 * One stem separation model. [StemSeparatorManager] picks the engine from the Fast / Max setting, then runs the shared
 * overlap-add loop ([StemOverlapAdd]) with [segmentSamples], [hopSamples], [stemCount] and [inferChunk].
 *
 * The stem order of [specs] is the order of the arrays [inferChunk] returns and of the cache files.
 */
interface StemEngine {
    /** Written in the cache "done" marker, e.g. "htdemucs6s". */
    val id: String

    /** Stems in output order. The mixer, cache and separator all read this one list. */
    val specs: List<StemSpec>

    val stemNames: List<String> get() = specs.map { it.label }
    val stemCount: Int get() = specs.size

    /** Model input length in frames (per channel). */
    val segmentSamples: Int

    /** Distance between the starts of two chunks. The overlap is `segmentSamples - hopSamples`. */
    val hopSamples: Int

    /** Cross-fade weights for the overlap-add, [segmentSamples] long. */
    val window: FloatArray

    /** Free RAM (bytes) the engine wants before it starts; 0 = no check. A heuristic, see the engine's comment. */
    val minFreeRamBytes: Long get() = 0L

    /** Why [initialize] or the last [inferChunk] failed (shown to the user), or null. */
    val lastError: String?

    /** Loads the model and checks its tensor contract. False (with [lastError] set) if that fails. */
    fun initialize(): Boolean

    /**
     * Separates one chunk. [inChunk] is [2][chunkSize] (left, right), already sanitised by the caller.
     * Returns [stemCount][2][chunkSize] in [specs] order, or null with [lastError] set.
     * The returned arrays are reused by the next call: read them before calling again. Every returned sample is
     * finite and within +-[StemDsp.MAX_ABS].
     */
    fun inferChunk(inChunk: Array<FloatArray>, chunkSize: Int): Array<Array<FloatArray>>?

    /** Frees the model session and its native memory. Safe to call more than once. */
    fun release()
}

/** One line for logs. */
fun StemEngine.describe(): String = "$id: ${specs.joinToString("/") { it.key }} ($segmentSamples/$hopSamples)"
