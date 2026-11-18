package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cadence maths for the presenter (pure Kotlin, unit tested on the JVM).
 *
 * A panel refreshing at H Hz can show a video at exactly H / n frames per second for a whole number n: every output
 * frame stays on screen for the same n vsyncs. Any other rate (45 or 90 FPS on a 120 Hz panel, 45 on 60 Hz) is shown
 * as an uneven mix, for example 3-3-2 vsyncs. That unevenness is judder, and no interpolation quality can hide it.
 *
 * The planner only offers targets the panel can show evenly (see [com.beatraxus.app.motionboost.MotionBoostTiming.isCleanRate]),
 * and the display mode is requested to match. This governor is the last line of defence at run time: when the system
 * did not grant the requested mode (battery saver, OEM refresh policy, thermal), the presenter still presents on an
 * exact n-vsync grid at the closest rate that is possible, instead of letting the cadence go uneven.
 */
object CadenceMath {

    /**
     * The whole number n (>= 1) that makes [displayHz] / n closest to [targetFps]. On a tie the larger n wins (the
     * lower, cheaper rate: a steady 60 beats a heavy 120 when 90 was asked on 120 Hz).
     */
    fun bestDivisor(targetFps: Int, displayHz: Float): Int {
        if (targetFps <= 0 || displayHz <= 0f) return 1
        if (targetFps >= displayHz) return 1
        val lo = (displayHz / targetFps).toInt().coerceAtLeast(1)
        var best = lo
        var bestErr = Float.MAX_VALUE
        for (n in lo..lo + 1) {
            val err = abs(displayHz / n - targetFps)
            if (err < bestErr - 1e-3f || (abs(err - bestErr) <= 1e-3f && n > best)) { best = n; bestErr = err }
        }
        return best
    }

    /** True when [targetFps] is [displayHz] / n for a whole n, within half a frame per second. */
    fun isExact(targetFps: Int, displayHz: Float): Boolean {
        if (targetFps <= 0 || displayHz <= 0f) return false
        val n = bestDivisor(targetFps, displayHz)
        return abs(displayHz / n - targetFps) < 0.5f
    }

    fun hzOf(vsyncPeriodNs: Long): Float = if (vsyncPeriodNs > 0L) 1_000_000_000f / vsyncPeriodNs else 0f
}

/**
 * Stateful wrapper used by each renderer: turns (target FPS, measured vsync period) into the exact step between two
 * presented frames, and remembers the last choice so a noisy period measurement can never make the cadence flip back
 * and forth between two equally good divisors.
 */
class CadenceGovernor {
    /** Vsyncs between two output frames (0 until the first [stepNs]). */
    var vsyncsPerFrame: Int = 0
        private set
    /** Panel refresh rate the last step was computed for. */
    var displayHz: Float = 0f
        private set
    private var lastTarget = 0

    /** Output rate that is really presented: [displayHz] / [vsyncsPerFrame] (0 until the first [stepNs]). */
    val outputFps: Float get() = if (vsyncsPerFrame > 0) displayHz / vsyncsPerFrame else 0f

    /** True when the output rate equals the requested target (within half a frame per second). */
    var exact: Boolean = true
        private set

    fun reset() { vsyncsPerFrame = 0; displayHz = 0f; lastTarget = 0; exact = true }

    /**
     * Step (ns) between two presented frames: [vsyncsPerFrame] whole vsync periods, so every frame is held for the
     * same time. Never returns less than one vsync.
     */
    fun stepNs(targetFps: Int, vsyncPeriodNs: Long): Long {
        if (targetFps <= 0 || vsyncPeriodNs <= 0L) return vsyncPeriodNs.coerceAtLeast(1L)
        val hz = CadenceMath.hzOf(vsyncPeriodNs)
        val best = CadenceMath.bestDivisor(targetFps, hz)
        var n = best
        val prev = vsyncsPerFrame
        if (prev > 0 && targetFps == lastTarget && prev != best) {
            // Same target, slightly different measurement: keep the old divisor unless the new one is clearly better.
            val prevErr = abs(hz / prev - targetFps)
            val bestErr = abs(hz / best - targetFps)
            if (prevErr <= bestErr * 1.15f + 0.5f) n = prev
        }
        vsyncsPerFrame = n
        displayHz = hz
        lastTarget = targetFps
        exact = abs(hz / n - targetFps) < 0.5f
        return n * vsyncPeriodNs
    }

    /** The rate that should be compared with the source rate to decide whether interpolation is worth running. */
    fun effectiveTarget(targetFps: Int): Int = if (vsyncsPerFrame > 0) outputFps.roundToInt().coerceAtLeast(1) else targetFps
}
