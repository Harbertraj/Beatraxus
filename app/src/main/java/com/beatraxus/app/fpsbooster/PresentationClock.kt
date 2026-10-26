package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming

/**
 * Presentation-time maths for the interpolating presenter (pure Kotlin, unit tested on the JVM).
 *
 * Why this exists: the decoder hands frames over a variable time ahead of their presentation time
 * ("lead", 20-50 ms and jittery with ExoPlayer). The old presenter switched to the newest frame pair the
 * moment a frame arrived and derived the playback delay from a running average of the lead. Whenever one
 * frame's lead differed from the average, the displayed content time stalled (alpha clamped at 0) or jumped,
 * which is exactly the "slightly shaky" motion that is visible on top of the interpolation.
 *
 * The fix is two-part:
 *  1. [HoldController]: the delay is derived from the *smallest* lead of a recent window (the latest a frame
 *     ever arrived), so a frame is practically never late, and the delay is slew-limited so it can never
 *     make content time jump.
 *  2. [FrameSelection]: frames are kept in a small queue and the pair that brackets the content time is
 *     chosen by timestamp, instead of "whatever arrived last". Early frames simply wait in the queue.
 */
class HoldController(private val window: Int = WINDOW) {
    private val leads = LongArray(window)
    private var count = 0
    private var next = 0
    private var holdNs = 0L
    private var initialised = false

    /** Forget everything (seek, new clip, resize). The next [update] snaps instead of slewing. */
    fun reset() {
        count = 0; next = 0; initialised = false
    }

    /** Records how far ahead of its presentation time a decoded frame arrived. */
    fun onFrame(leadNs: Long) {
        leads[next] = leadNs.coerceIn(MIN_LEAD_NS, MAX_LEAD_NS)
        next = (next + 1) % window
        if (count < window) count++
    }

    /** Smallest lead in the window (the worst case), or [fallbackNs] when nothing was measured yet. */
    fun minLeadNs(fallbackNs: Long = DEFAULT_LEAD_NS): Long {
        if (count == 0) return fallbackNs
        var m = Long.MAX_VALUE
        for (i in 0 until count) if (leads[i] < m) m = leads[i]
        return m
    }

    /**
     * Hold (ns) to use on this vsync. Moves towards the target by at most 1/[SLEW_UP_DIV] (growing) or
     * 1/[SLEW_DOWN_DIV] (shrinking) of a vsync per call, i.e. content time speeds up / slows down by a few
     * percent at most while it settles, never a visible jump.
     *
     * [estimateNs] is the measured time the motion estimate of a pair takes from the arrival of its second frame
     * until the GPU has finished it (see OpticalFlowRenderer: fence latency, smoothed). A pair can only be shown
     * once its flow exists, so the hold has to cover that time on top of the decoder lead; without it a slow
     * estimate (Quality preset) makes every pair "not ready yet" exactly when it is due. Capped at [MAX_ESTIMATE_NS].
     */
    fun update(frameIntervalNs: Long, vsyncPeriodNs: Long, estimateNs: Long = 0L): Long {
        val target = MotionBoostTiming.holdNs(
            frameIntervalNs, minLeadNs(), vsyncPeriodNs, estimateNs.coerceIn(0L, MAX_ESTIMATE_NS)
        )
        if (!initialised) {
            holdNs = target; initialised = true
            return holdNs
        }
        val up = vsyncPeriodNs / SLEW_UP_DIV
        val down = vsyncPeriodNs / SLEW_DOWN_DIV
        holdNs += (target - holdNs).coerceIn(-down, up)
        return holdNs
    }

    companion object {
        const val WINDOW = 16
        const val SLEW_UP_DIV = 16L
        const val SLEW_DOWN_DIV = 32L
        const val DEFAULT_LEAD_NS = 20_000_000L
        const val MAX_ESTIMATE_NS = 100_000_000L
        private const val MIN_LEAD_NS = -50_000_000L
        private const val MAX_LEAD_NS = 200_000_000L
    }
}

object FrameSelection {
    /**
     * [a] and [b] are indices into the frame queue. [a] == [b] means "show that single real frame"
     * (content time is before the oldest or after the newest queued frame); otherwise the output is built
     * from frames [a] and [b] at [alpha] (0 = a, 1 = b).
     */
    data class Pick(val a: Int, val b: Int, val alpha: Float)

    /**
     * Chooses the pair of queued frames (ascending timestamps, [count] of them, read through [tsAt]) that
     * brackets [contentNs]. Requires [count] >= 1.
     */
    inline fun pick(count: Int, tsAt: (Int) -> Long, contentNs: Long): Pick {
        val last = count - 1
        if (count == 1 || contentNs >= tsAt(last)) return Pick(last, last, 1f)
        if (contentNs <= tsAt(0)) return Pick(0, 0, 0f)
        var i = 0
        while (i + 1 < last && tsAt(i + 1) <= contentNs) i++
        return Pick(i, i + 1, MotionBoostTiming.alpha(contentNs, tsAt(i), tsAt(i + 1)))
    }
}
