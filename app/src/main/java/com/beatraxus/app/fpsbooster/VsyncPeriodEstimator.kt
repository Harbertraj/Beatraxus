package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Panel vsync period from the gaps between two presenter callbacks (pure Kotlin, unit tested on the JVM).
 *
 * A callback that arrives late (the GL thread was still busy) is not a slower panel: Choreographer skips whole vsyncs, so
 * the gap is 2, 3 ... periods. Lateness only ever LENGTHENS a gap, so the true period is the cluster of the shortest gaps.
 * The old median of the last 9 gaps turned into "45 Hz" on a 90 Hz panel (and "60 Hz" on a 120 Hz panel) as soon as 5 of
 * them were late, and [CadenceGovernor] then presented at that lower rate: the target was silently given up exactly when
 * the device was loaded, and the quality controller never saw the missed frames.
 *
 * Two layers:
 *  1. Cluster of the shortest gaps over the last [WINDOW] callbacks (index count / 8 of the sorted gaps, not the minimum,
 *     so one stray tiny gap cannot define the period), then the median of the gaps within [CLUSTER_PERCENT] % of it.
 *  2. The refresh rate the system reports for the panel (`reportedHz`, 0 = unknown): when the estimate is a whole multiple
 *     (2, 3 ...) of the reported period, the callbacks are late, not the panel slow, and the reported period is used. A
 *     real mode change (120 -> 90, not a whole multiple) is followed as measured.
 */
class VsyncPeriodEstimator(private val window: Int = WINDOW) {
    private val ring = LongArray(window)
    private val scratch = LongArray(window)
    private var head = 0
    private var count = 0

    /** Current estimate in ns ([DEFAULT_PERIOD_NS] until the first usable gap). Kept across [reset]. */
    var periodNs: Long = DEFAULT_PERIOD_NS
        private set

    /** Forget the gaps (new surface / new session). The last estimate stays until new gaps replace it. */
    fun reset() { head = 0; count = 0 }

    /** Feeds the gap (ns) between two callbacks and returns the updated period. Gaps outside 3..40 ms are ignored. */
    fun onGap(gapNs: Long, reportedHz: Float = 0f): Long {
        if (gapNs < MIN_GAP_NS || gapNs > MAX_GAP_NS) return periodNs
        ring[head] = gapNs
        head = (head + 1) % window
        if (count < window) count++

        System.arraycopy(ring, 0, scratch, 0, count)
        java.util.Arrays.sort(scratch, 0, count)
        val base = scratch[count / 8]
        val lo = base - base * CLUSTER_PERCENT / 100
        val hi = base + base * CLUSTER_PERCENT / 100
        var s = 0
        while (scratch[s] < lo) s++
        var e = s
        while (e < count && scratch[e] <= hi) e++
        var period = scratch[s + (e - s) / 2]

        if (reportedHz > 1f) {
            val reportedNs = 1e9 / reportedHz
            val ratio = period / reportedNs
            val k = ratio.roundToInt()
            if (k >= 2 && abs(ratio - k) <= LATE_TOLERANCE * k) period = reportedNs.roundToLong()
        }
        periodNs = period
        return period
    }

    companion object {
        const val WINDOW = 32
        const val MIN_GAP_NS = 3_000_000L
        const val MAX_GAP_NS = 40_000_000L
        const val CLUSTER_PERCENT = 15
        /** How far (share of the multiple) the estimate may be from a whole multiple of the reported period. */
        const val LATE_TOLERANCE = 0.06
        const val DEFAULT_PERIOD_NS = 8_333_333L
    }
}
