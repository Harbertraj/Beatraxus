package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming

/**
 * Pure timestamp / scheduling maths for the interpolating presenter. The Android renderer applies the same
 * rules per vsync; this object exists so they can be verified on the JVM (24 -> 30, 24 -> 60, odd cadences,
 * seeks and pauses) without a GPU.
 */
object FramePacing {

    /** Phase window treated as "a real source frame" rather than a generated one. */
    private const val GENERATED_MIN = 0.02f
    private const val GENERATED_MAX = 0.98f

    /** One output frame: built from source frames [pair] and [pair]+1 at position [alpha] (0 = pair, 1 = pair+1). */
    data class OutputFrame(val pair: Int, val alpha: Float, val timeNs: Long, val interpolated: Boolean) {
        /** True when the picture is synthesised rather than (a copy of) a decoded frame. */
        val generated: Boolean get() = interpolated && alpha in GENERATED_MIN..GENERATED_MAX
    }

    fun intervalNs(fps: Int): Long = (1_000_000_000.0 / fps.coerceAtLeast(1)).toLong()

    /**
     * Output schedule for decoded frames with presentation times [sourceTsNs] (ascending) at [targetFps].
     * Across a discontinuity (seek / long pause, see [MotionBoostTiming.isContinuous]) nothing is invented:
     * the earlier frame is held with alpha 0 until the next real frame.
     */
    fun schedule(sourceTsNs: LongArray, targetFps: Int): List<OutputFrame> {
        if (sourceTsNs.size < 2) return emptyList()
        val step = intervalNs(targetFps)
        val out = ArrayList<OutputFrame>()
        var t = sourceTsNs.first()
        val end = sourceTsNs.last()
        var i = 0
        while (t < end) {
            while (i + 2 < sourceTsNs.size && sourceTsNs[i + 1] <= t) i++
            val a = sourceTsNs[i]
            val b = sourceTsNs[i + 1]
            val continuous = MotionBoostTiming.isContinuous(a, b)
            val alpha = if (continuous) MotionBoostTiming.alpha(t, a, b) else 0f
            out += OutputFrame(i, alpha, t, continuous)
            t += step
        }
        return out
    }

    /**
     * Fraction of output frames that are synthesised for a steady [sourceFps] -> [targetFps] conversion.
     *
     * An output frame equals a decoded frame only when the two clocks line up. For integer rates that happens
     * once every `target / gcd(target, source)` output frames, so e.g. 24 -> 60 shows a decoded frame only 1 time
     * in 5 (80 % synthesised), 24 -> 48 one in 2 (50 %), 30 -> 60 one in 2. For fractional rates (23.976) the
     * clocks drift, so practically every frame is synthesised.
     */
    fun generatedShare(sourceFps: Float, targetFps: Int): Float {
        if (sourceFps <= 0f || targetFps <= sourceFps) return 0f
        val rounded = Math.round(sourceFps)
        if (Math.abs(sourceFps - rounded) > 0.01f) return 1f
        val g = gcd(targetFps, rounded)
        val period = targetFps / g
        return 1f - 1f / period
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Largest gap (ns) between consecutive output times: a quick judder measure for a schedule. */
    fun maxGapNs(frames: List<OutputFrame>): Long =
        frames.zipWithNext { a, b -> b.timeNs - a.timeNs }.maxOrNull() ?: 0L

    /**
     * Content time (ns) shown on the vsync at [vsyncNs] given the source clock offset. Presented one vsync
     * after it is drawn and held back by [holdNs] so the next source frame has arrived (see MotionBoostTiming.holdNs).
     */
    fun contentTimeNs(vsyncNs: Long, vsyncPeriodNs: Long, holdNs: Long): Long = vsyncNs + vsyncPeriodNs - holdNs

    /**
     * Number of output frames that were due in the window but not presented, given how many were presented
     * and the rate that should have been presented. Never negative.
     */
    fun missedOutputs(expectedFps: Float, windowNs: Long, presented: Int): Int {
        val expected = Math.round(expectedFps * windowNs / 1e9f)
        return (expected - presented).coerceAtLeast(0)
    }

    /** Rate that can actually be presented: the target, but never faster than the panel's best matching mode. */
    fun achievableFps(targetFps: Int, panelRates: List<Float>): Int {
        val peak = panelRates.maxOrNull() ?: 60f
        return minOf(targetFps.toFloat(), peak).toInt()
    }
}
