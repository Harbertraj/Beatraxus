package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the booster picks its output rate.
 *
 *  - [GENERATE]: the original behaviour. The user (or Auto) picks a target FPS and every output frame is generated at its own
 *    position in time, whatever the ratio to the source is (24 -> 60 is 2.5x, so the positions differ from frame to frame).
 *  - [MULTIPLE]: video-player style. The output is a WHOLE multiple of the source rate (24 -> 120 is 5x, 30 -> 60 / 90 / 120 is
 *    2x / 3x / 4x), the real frames are shown untouched and every generated frame sits at the same fixed distance between two
 *    real frames ([PhaseLock]). Even spacing looks smoother than a mixed ratio, and the generated frames have the same
 *    (small) motion step every time.
 */
enum class BoosterStyle(val label: String) {
    GENERATE("Generate"),
    MULTIPLE("Multiple");

    companion object {
        fun fromName(name: String?): BoosterStyle = entries.firstOrNull { it.name == name } ?: GENERATE
    }
}

/** One whole-multiple choice: [n] x the source = [targetFps]. */
data class MultipleOption(val n: Int, val targetFps: Int)

object MultipleMode {
    const val MIN_N = 2
    const val MAX_N = 5
    /** A target counts as "n x the source" when it is within this share of it (23.976 x 5 = 119.88 -> 120 FPS). */
    const val EXACT_TOLERANCE = 0.02f
    /** Extra delay (share of one source interval) on top of the normal video hold: more time for each motion estimate. */
    const val EXTRA_HOLD_SHARE = 0.25f

    /**
     * The whole multiples of [sourceFps] (2..[MAX_N]) whose rate the panel shows evenly and that do not exceed [ceiling],
     * ascending. Empty when the source rate is unknown or no multiple fits (25 FPS on a 60 / 90 / 120 Hz panel).
     */
    fun options(sourceFps: Float, panelRates: List<Float>, ceiling: Int): List<MultipleOption> {
        if (sourceFps <= 0f) return emptyList()
        return (MIN_N..MAX_N).mapNotNull { n ->
            val target = (sourceFps * n).roundToInt()
            val exact = kotlin.math.abs(target - sourceFps * n) / (sourceFps * n) <= EXACT_TOLERANCE
            if (exact && target in 1..ceiling && target in BoosterTargets.ALL && MotionBoostTiming.isCleanRate(panelRates, target))
                MultipleOption(n, target) else null
        }
    }

    /** n when [targetFps] is a whole multiple (2 or more) of [sourceFps] within [EXACT_TOLERANCE], else 0 (not phase-locked). */
    fun phaseCount(targetFps: Int, sourceFps: Float): Int {
        if (sourceFps <= 0f || targetFps <= 0) return 0
        val n = (targetFps / sourceFps).roundToInt()
        if (n < MIN_N) return 0
        return if (kotlin.math.abs(targetFps - sourceFps * n) / (sourceFps * n) <= EXACT_TOLERANCE) n else 0
    }

    fun extraHoldNs(frameIntervalNs: Long): Long = (frameIntervalNs * EXTRA_HOLD_SHARE).toLong()
}

/**
 * Locks the output frames to the source frame grid (pure Kotlin, unit tested on the JVM).
 *
 * With n outputs per source frame the generated frames should sit at 1/n, 2/n ... (n-1)/n of the way between two real frames and
 * the output at 0 should BE the real frame. The presenter's own content time has an arbitrary phase against the source
 * timestamps, so two things are done:
 *  - [snap] moves each output position to the nearest grid point (a point at 0 / 1 is shown as the real frame itself);
 *  - a slow servo ([offsetNs]) measures the average phase error (a circular mean: no unstable half-way point) and shifts the content
 *    time by it, so the snap only ever corrects a small, steady remainder instead of flipping between two grid points.
 * The offset is slew-limited and bounded to one grid step, so the picture never jumps.
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
     * Records where the output frame would fall ([alpha] of a pair, 0..1) and returns the snapped position (0..1).
     * [n] = outputs per source frame, [intervalNs] = source frame interval.
     */
    fun lock(alpha: Float, n: Int, intervalNs: Long): Float {
        if (n < MultipleMode.MIN_N) return alpha
        val k = (alpha * n).roundToInt()
        val a = 2.0 * PI * (alpha * n - Math.floor(alpha * n.toDouble()))
        c += (cos(a) - c) * AVERAGE
        s += (sin(a) - s) * AVERAGE
        if (seen < WARMUP) seen++
        if (seen >= WARMUP && hypot(c, s) > MIN_STRENGTH && intervalNs > 0L) {
            val stepNs = intervalNs.toDouble() / n
            val error = atan2(s, c) / (2.0 * PI)          // mean phase error in grid steps (-0.5..0.5)
            // The measured phase already contains the offset applied so far, so this is an integrator: output positions that are
            // "error" grid steps past a grid point move the content time back by a small share of that, every frame.
            val slew = (stepNs * SLEW).toLong().coerceAtLeast(1L)
            val move = (-error * stepNs * GAIN).toLong().coerceIn(-slew, slew)
            offsetNs = (offsetNs + move).coerceIn(-stepNs.toLong(), stepNs.toLong())
        }
        return (k.toFloat() / n).coerceIn(0f, 1f)
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
    }
}
