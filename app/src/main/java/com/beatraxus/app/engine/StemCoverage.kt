package com.beatraxus.app.engine

/**
 * Which frames (44.1 kHz) of a song's stems are already written and final. Immutable, so the audio thread can read a
 * snapshot without a lock while the separator publishes a new one after every flush.
 *
 * Ranges are half-open `[start, end)`, sorted and merged.
 */
class StemCoverage private constructor(private val starts: LongArray, private val ends: LongArray) {

    val rangeCount: Int get() = starts.size

    /** Total number of covered frames. */
    val coveredFrames: Long
        get() {
            var n = 0L
            for (i in starts.indices) n += ends[i] - starts[i]
            return n
        }

    /** True when every frame of `[from, to)` is covered (an empty range counts as covered). */
    fun contains(from: Long, to: Long): Boolean {
        if (to <= from) return true
        for (i in starts.indices) {
            if (from >= starts[i] && to <= ends[i]) return true
        }
        return false
    }

    /** A new coverage with `[start, end)` added (merged with touching or overlapping ranges). */
    fun plus(start: Long, end: Long): StemCoverage {
        if (end <= start) return this
        val s = ArrayList<Long>()
        val e = ArrayList<Long>()
        var ns = start
        var ne = end
        var placed = false
        for (i in starts.indices) {
            when {
                ends[i] < ns -> { s += starts[i]; e += ends[i] }
                starts[i] > ne -> {
                    if (!placed) { s += ns; e += ne; placed = true }
                    s += starts[i]; e += ends[i]
                }
                else -> { ns = minOf(ns, starts[i]); ne = maxOf(ne, ends[i]) }
            }
        }
        if (!placed) { s += ns; e += ne }
        return StemCoverage(s.toLongArray(), e.toLongArray())
    }

    companion object {
        val EMPTY = StemCoverage(LongArray(0), LongArray(0))

        /** Everything (a finished song). */
        val FULL = StemCoverage(longArrayOf(0L), longArrayOf(Long.MAX_VALUE))
    }
}
