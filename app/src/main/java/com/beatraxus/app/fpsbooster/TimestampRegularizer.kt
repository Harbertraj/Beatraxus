package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import kotlin.math.max

/**
 * Removes vsync quantisation from source-frame timestamps (pure Kotlin, unit tested on the JVM).
 *
 * ExoPlayer releases a decoded frame on a display vsync, so SurfaceTexture.getTimestamp() is the true
 * presentation time snapped to the panel refresh grid. A steady 24 fps film on a 60 Hz panel therefore arrives
 * as 33 / 50 / 33 / 50 ms steps, 25 fps as 33 / 50 / 50 ... The interpolator derives its phase (alpha) from
 * those stamps, so the quantisation shows up as periodic judder in the generated frames even when every
 * generated frame is perfect.
 *
 * Model: source frames are evenly spaced. The interval is the least-squares slope of the last [window] raw
 * stamps, and each output stamp is the predicted next stamp pulled towards the raw one by 1/[PHASE_DIV] so the
 * clock still follows the real one (A/V sync is preserved; the output never drifts from the raw stamps by more
 * than a vsync or so). The first [MIN_SAMPLES] frames of a run pass through unchanged. A stamp far from the
 * prediction (a seek, a pause, a dropped frame, variable frame rate) resets the filter and passes through,
 * so nothing is ever smoothed across a discontinuity.
 */
class TimestampRegularizer(private val window: Int = WINDOW) {
    private val pts = PtsLock()
    private val raw = LongArray(window)
    private var count = 0
    private var head = 0          // next write position
    private var lastOutNs = 0L

    /** Estimated source interval in ns, 0 until [MIN_SAMPLES] stamps of a run have been seen. */
    var intervalNs: Long = 0L
        private set

    fun reset() {
        count = 0; head = 0; intervalNs = 0L; lastOutNs = 0L
        pts.reset(); lastPtsOutNs = 0L
    }

    /**
     * Stamp for a frame whose container presentation time is known ([ptsNs] >= 0, from [FramePts]). The container
     * times are the exact spacing of the source frames (no vsync quantisation at all), so once they agree with the
     * raw stamps over a few frames the output is `pts + offset` and every interval is the real one. Anything the
     * model cannot explain (speed other than 1x, a seek, a pause, a missing time) uses the statistical path below.
     * The statistical path is fed every frame, so it is warm whenever the container times are given up.
     */
    fun next(rawNs: Long, vsyncPeriodNs: Long, ptsNs: Long): Long {
        val statistical = next(rawNs, vsyncPeriodNs)
        if (ptsNs < 0L) { pts.reset(); return statistical }
        val locked = pts.map(rawNs, ptsNs, vsyncPeriodNs)
        if (locked == Long.MIN_VALUE) return statistical
        // Monotonic, and never further from the raw stamp than the statistical path allows (A/V sync preserved).
        val out = if (locked <= lastPtsOutNs) lastPtsOutNs + 1L else locked
        lastPtsOutNs = out
        return out
    }

    private var lastPtsOutNs = 0L

    /** True while the output follows the container presentation times. */
    val ptsLocked: Boolean get() = pts.locked

    /** Regularised stamp for [rawNs]; [vsyncPeriodNs] sets how far a stamp may deviate before it counts as a break. */
    fun next(rawNs: Long, vsyncPeriodNs: Long): Long {
        if (count > 0) {
            val last = raw[(head - 1 + window) % window]
            val gap = rawNs - last
            if (gap <= 0L || gap > MAX_STEP_NS) reset()
        }
        if (count >= MIN_SAMPLES) {
            val predicted = lastOutNs + intervalNs
            val tolerance = max(vsyncPeriodNs * 3 / 2, intervalNs / 2)
            if (abs(rawNs - predicted) > tolerance) reset()
        }
        raw[head] = rawNs
        head = (head + 1) % window
        if (count < window) count++
        if (count < MIN_SAMPLES) {
            lastOutNs = rawNs
            return rawNs
        }
        intervalNs = slopeNs()
        val predicted = lastOutNs + intervalNs
        var out = predicted + (rawNs - predicted) / PHASE_DIV
        if (out <= lastOutNs) out = lastOutNs + 1L
        lastOutNs = out
        return out
    }

    /** Least-squares slope (ns per frame) of the stored stamps against their index. */
    private fun slopeNs(): Long {
        val n = count
        val first = (head - n + window) % window
        val t0 = raw[first]
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until n) {
            sumX += i
            sumY += (raw[(first + i) % window] - t0).toDouble()
        }
        val mx = sumX / n
        val my = sumY / n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = i - mx
            sxy += dx * ((raw[(first + i) % window] - t0).toDouble() - my)
            sxx += dx * dx
        }
        return if (sxx > 0.0) Math.round(sxy / sxx) else 0L
    }

    companion object {
        const val WINDOW = 24
        const val MIN_SAMPLES = 6
        const val PHASE_DIV = 8L
        /** A raw step longer than this is a seek / pause, never a frame interval. */
        const val MAX_STEP_NS = 400_000_000L
    }
}

/**
 * Follows `raw - pts` (the offset between the renderer's clock and the container's) with a slow filter, so that
 * `pts + offset` has the exact spacing of the container times and the average position of the raw stamps.
 * Returns [Long.MIN_VALUE] until [LOCK_FRAMES] consecutive frames agreed with the model.
 */
internal class PtsLock {
    private var offsetNs = 0.0
    private var lastPtsNs = Long.MIN_VALUE
    private var lastRawNs = 0L
    private var agreeing = 0

    val locked: Boolean get() = agreeing >= LOCK_FRAMES

    fun reset() { agreeing = 0; lastPtsNs = Long.MIN_VALUE }

    fun map(rawNs: Long, ptsNs: Long, vsyncPeriodNs: Long): Long {
        val sample = (rawNs - ptsNs).toDouble()
        val ptsStep = ptsNs - lastPtsNs
        val rawStep = rawNs - lastRawNs
        val first = lastPtsNs == Long.MIN_VALUE
        lastPtsNs = ptsNs
        lastRawNs = rawNs
        if (first || ptsStep <= 0L || ptsStep > TimestampRegularizer.MAX_STEP_NS || rawStep <= 0L) {
            offsetNs = sample; agreeing = 1
            return Long.MIN_VALUE
        }
        val tolerance = vsyncPeriodNs * 3 / 2 + 2_000_000L
        if (kotlin.math.abs(sample - offsetNs) > tolerance) {
            // The two clocks disagree by more than vsync quantisation can explain: new run (seek, pause, speed change).
            offsetNs = sample; agreeing = 1
            return Long.MIN_VALUE
        }
        offsetNs += (sample - offsetNs) / OFFSET_DIV
        if (agreeing < LOCK_FRAMES) agreeing++
        return if (locked) ptsNs + Math.round(offsetNs) else Long.MIN_VALUE
    }

    companion object {
        const val LOCK_FRAMES = 6
        const val OFFSET_DIV = 16.0
    }
}
