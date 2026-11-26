package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How the booster picks its output rate.
 *
 *  - [GENERATE]: the original behaviour. The user (or Auto) picks a target FPS and every output frame is generated at its own
 *    position in time, whatever the ratio to the source is (24 -> 60 is 2.5x, so the positions differ from frame to frame).
 *  - [MULTIPLE]: video-player style. The output is target / source times the source rate, a whole or a FRACTIONAL multiple
 *    (24 -> 120 is 5x, 25 -> 60 is 2.4x, 24 -> 90 is 3.75x). The output frames are evenly spaced in time and each one is placed
 *    by its exact timestamp between two real frames; the servo in [PhaseLock] aligns the output grid with the source frames so
 *    the real frames are shown untouched whenever the two clocks coincide and the generated ones fill the exact gaps.
 */
enum class BoosterStyle(val label: String) {
    GENERATE("Generate"),
    MULTIPLE("Multiple");

    companion object {
        fun fromName(name: String?): BoosterStyle = entries.firstOrNull { it.name == name } ?: GENERATE
    }
}

/**
 * One Multiple choice: [targetFps] output frames per second from the current source, i.e. [multiplier] = target / source
 * (24 -> 60 is 2.5x, 25 -> 120 is 4.8x). The multiplier is exact (not rounded to a whole number) and is always computed from the
 * source rate it was offered for, so it can never be stale.
 */
data class MultipleOption(val targetFps: Int, val multiplier: Double) {
    /** "60 FPS · 2.40x" (the UI chip text). Locale independent. */
    val label: String get() = "$targetFps FPS \u00B7 ${MultipleMode.formatMultiplier(multiplier)}"
}

/**
 * [outputs] output frames fall on every [sources] source frames, in lowest terms: 25 -> 60 FPS is 12 : 5 (12 outputs per 5 source
 * frames, so a source interval holds two or three output intervals), 24 -> 120 is 5 : 1, 30 -> 90 is 3 : 1.
 * Output k sits at source position k * [sources] / [outputs] (in source frames), so its phase inside a source interval is always a
 * multiple of 1 / [outputs]. A ratio with [sources] == 1 is the old whole-number case.
 */
data class FrameRatio(val outputs: Int, val sources: Int) {
    val isInteger: Boolean get() = sources == 1
    val value: Double get() = outputs.toDouble() / sources
}

object MultipleMode {
    /** Smallest multiplier worth interpolating for (50 -> 60 FPS is 1.2x). Below it the video is "already at the target". */
    const val MIN_MULTIPLIER = 1.2
    /** The multiplier is compared with this slack so 60 / 50 (1.19999..) still counts as 1.2x. */
    private const val MIN_SLACK = 1e-6
    /** A ratio is approximated by p : q when it is within this share of the measured one (23.976 -> 60 = 2.5025 = 5 : 2). */
    const val RATIO_TOLERANCE = 0.004
    /** Largest number of source frames in one repeating pattern (25 -> 90 needs 5, 29.97 -> 45 needs 2). */
    const val MAX_PATTERN_SOURCES = 16
    /** Extra delay (share of one source interval) on top of the normal video hold: more time for each motion estimate. */
    const val EXTRA_HOLD_SHARE = 0.25f

    /** target / source, exactly (0 when either is unknown). */
    fun multiplier(targetFps: Int, sourceFps: Float): Double =
        if (targetFps <= 0 || sourceFps <= 0f) 0.0 else targetFps.toDouble() / sourceFps.toDouble()

    /** "2.40x", "5x", "3.75x": two decimals at most, trailing zeros dropped (the dot is always a dot). */
    fun formatMultiplier(m: Double): String {
        val t = String.format(java.util.Locale.US, "%.2f", m).trimEnd('0').trimEnd('.')
        return t + "x"
    }

    /**
     * Every target FPS the user may pick for a source at [sourceFps]: targets of [BoosterTargets.ALL] that the panel shows evenly
     * ([MotionBoostTiming.isCleanRate]), that do not exceed [ceiling] and that are at least [MIN_MULTIPLIER] above the source.
     * Ascending. Empty when the source rate is unknown or nothing fits. Nothing is hardcoded per source rate.
     */
    fun options(sourceFps: Float, panelRates: List<Float>, ceiling: Int): List<MultipleOption> {
        if (sourceFps <= 0f) return emptyList()
        return BoosterTargets.ALL.mapNotNull { target ->
            val m = multiplier(target, sourceFps)
            if (target <= ceiling && m >= MIN_MULTIPLIER - MIN_SLACK && MotionBoostTiming.isCleanRate(panelRates, target))
                MultipleOption(target, m) else null
        }
    }

    /**
     * Lowest-terms ratio of [targetFps] to [sourceFps], or null when the source is unknown or no pattern of at most
     * [MAX_PATTERN_SOURCES] source frames reproduces the ratio within [RATIO_TOLERANCE] (then the output is still placed by exact
     * timestamps, it just has no repeating pattern to lock to). Source rates that are nominal-but-not-exact (23.976, 29.97, 59.94)
     * resolve to the pattern of their nominal rate.
     */
    fun ratio(targetFps: Int, sourceFps: Float): FrameRatio? {
        if (targetFps <= 0 || sourceFps <= 0f) return null
        val r = targetFps.toDouble() / sourceFps.toDouble()
        if (r < 1.0) return null
        for (q in 1..MAX_PATTERN_SOURCES) {
            val p = Math.round(r * q).toInt()
            if (p <= q) continue
            if (kotlin.math.abs(p.toDouble() / q - r) / r <= RATIO_TOLERANCE) {
                val g = gcd(p, q)
                return FrameRatio(p / g, q / g)
            }
        }
        return null
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    fun extraHoldNs(frameIntervalNs: Long): Long = (frameIntervalNs * EXTRA_HOLD_SHARE).toLong()

    /**
     * Drift-free output times: output [k] after the anchor, [targetFps] frames per second. Computed from the index every time
     * (never by adding a rounded step), so after an hour the error is still under one nanosecond.
     */
    fun outputTimeNs(anchorNs: Long, k: Long, targetFps: Int): Long =
        anchorNs + Math.round(k * 1_000_000_000.0 / targetFps.coerceAtLeast(1))
}

/**
 * Snaps a frame rate to the nearest standard video rate (within [TOLERANCE]): a rate measured from presentation times is never
 * exact (24.8 for a 25 FPS file), and a whole multiple is decided on the nominal rate. Anything else is returned unchanged.
 */
object SourceRate {
    private val NOMINAL = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f)
    const val TOLERANCE = 0.025f

    fun snap(fps: Float): Float {
        if (fps <= 0f) return fps
        var best = fps
        var bestErr = Float.MAX_VALUE
        for (n in NOMINAL) {
            val err = kotlin.math.abs(fps - n) / n
            if (err < bestErr) { bestErr = err; best = n }
        }
        return if (bestErr <= TOLERANCE) best else fps
    }
}

/**
 * Aligns the output frame grid with the source frame grid (pure Kotlin, unit tested on the JVM).
 *
 * With the ratio p : q (see [FrameRatio]) every output frame sits at a phase of a multiple of 1/p inside its source interval, once
 * the output clock is aligned. The presenter's own content time has an arbitrary phase against the source timestamps, so:
 *  - [observe] feeds a slow servo ([offsetNs]) that measures the average phase error modulo 1/p (a circular mean: no unstable
 *    half-way point) and shifts the content time by it. The offset is slew-limited and bounded to one grid step, so the picture
 *    never jumps, and it is the only thing that moves the output: positions are NOT rounded to a grid for fractional ratios.
 *  - [place] returns the position to draw. For a whole-number ratio (q = 1) it snaps to the nearest k/p as before. For a
 *    fractional ratio the exact timestamp position is kept (output frames are NOT forced onto an integer grid, so no source
 *    interval is given the same number of outputs as the next); only a position within a hair of a real frame is shown as that
 *    real frame, so the original frame is never replaced by a near-identical synthetic one.
 * [reset] must be called on a seek, a restart, a new source rate or a new target / refresh rate.
 */
class PhaseLock {
    private var c = 0.0
    private var s = 0.0
    private var seen = 0
    /** Shift (ns) to ADD to the content time so that output frames land on the grid. */
    var offsetNs: Long = 0L
        private set

    fun reset() { c = 0.0; s = 0.0; seen = 0; offsetNs = 0L }

    /**
     * Records where an output frame fell ([alpha] of a pair, 0..1) for a ratio with [grid] = p distinct phases. [intervalNs] is
     * the source frame interval. Allocation free.
     */
    fun observe(alpha: Float, grid: Int, intervalNs: Long) {
        if (grid < MIN_GRID) return
        val a = 2.0 * PI * (alpha * grid - Math.floor(alpha * grid.toDouble()))
        c += (cos(a) - c) * AVERAGE
        s += (sin(a) - s) * AVERAGE
        if (seen < WARMUP) seen++
        if (seen >= WARMUP && hypot(c, s) > MIN_STRENGTH && intervalNs > 0L) {
            val stepNs = intervalNs.toDouble() / grid
            val error = atan2(s, c) / (2.0 * PI)          // mean phase error in grid steps (-0.5..0.5)
            // The measured phase already contains the offset applied so far, so this is an integrator: output positions that are
            // "error" grid steps past a grid point move the content time back by a small share of that, every frame.
            val slew = (stepNs * SLEW).toLong().coerceAtLeast(1L)
            val move = (-error * stepNs * GAIN).toLong().coerceIn(-slew, slew)
            offsetNs = (offsetNs + move).coerceIn(-stepNs.toLong(), stepNs.toLong())
        }
    }

    /** [observe] then [place]: the position (0..1) to draw for an output frame at [alpha]. */
    fun lock(alpha: Float, ratio: FrameRatio, intervalNs: Long): Float {
        observe(alpha, ratio.outputs, intervalNs)
        return place(alpha, ratio)
    }

    companion object {
        /** Weight of one frame in the circular average (about 10 frames). */
        const val AVERAGE = 0.1
        /** Share of the measured phase error corrected per frame (integral gain; small, because the average lags a few frames). */
        const val GAIN = 0.02
        const val WARMUP = 8
        /** Phase vectors shorter than this (a noisy, unlocked phase) are not trusted. */
        const val MIN_STRENGTH = 0.15
        /** Largest servo move per frame, in grid steps. */
        const val SLEW = 0.01
        /** Fewer than two phases per pattern means nothing is generated. */
        const val MIN_GRID = 2
        /** A fractional position this close (share of a source interval, scaled down for dense grids) to 0 / 1 is the real frame. */
        const val REAL_FRAME_TOL = 0.02f

        /** The position to draw for an output frame at [alpha] (see the class comment). Pure. */
        fun place(alpha: Float, ratio: FrameRatio): Float {
            if (ratio.outputs < MIN_GRID) return alpha
            if (ratio.isInteger) return (Math.round(alpha * ratio.outputs).toFloat() / ratio.outputs).coerceIn(0f, 1f)
            val tol = minOf(REAL_FRAME_TOL, 0.15f / ratio.outputs)
            return when {
                alpha <= tol -> 0f
                alpha >= 1f - tol -> 1f
                else -> alpha
            }
        }
    }
}
