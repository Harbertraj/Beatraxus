package com.beatraxus.app.motionboost

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure timing / capability maths for the FPS Booster, kept free of Android classes so it can be
 * unit tested on the JVM.
 */
object MotionBoostTiming {

    /** Extra safety margin (ns) added to the presentation hold so a late decoder frame does not stutter. */
    private const val HOLD_MARGIN_NS = 4_000_000L
    private const val MAX_HOLD_NS = 150_000_000L

    /** A source frame this many ns after the previous one (or earlier than it) is a seek / pause / new clip. */
    const val DISCONTINUITY_NS = 1_000_000_000L

    /**
     * How long (ns) the renderer holds playback back so that the *next* source frame has already
     * arrived when an interpolated frame between the two is needed.
     *
     * ExoPlayer hands the renderer a frame [leadNs] before it should be on screen, so the hold is the
     * source frame interval minus that lead, plus one vsync of pipeline latency and a small margin.
     */
    fun holdNs(frameIntervalNs: Long, leadNs: Long, vsyncPeriodNs: Long): Long =
        (frameIntervalNs - leadNs + vsyncPeriodNs + HOLD_MARGIN_NS).coerceIn(0L, MAX_HOLD_NS)

    /** Position (0..1) of [targetNs] between the previous and current source frame. */
    fun alpha(targetNs: Long, prevTsNs: Long, curTsNs: Long): Float {
        val span = curTsNs - prevTsNs
        if (span <= 0L) return 1f
        return ((targetNs - prevTsNs).toDouble() / span.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** Sources that are already (nearly) at the target rate need no interpolation. */
    fun shouldBypass(sourceFps: Float, targetFps: Int): Boolean =
        sourceFps <= 0f || sourceFps >= targetFps * 0.95f

    /** True when the gap between two source timestamps is a normal frame step rather than a seek or pause. */
    fun isContinuous(prevTsNs: Long, newTsNs: Long): Boolean {
        val delta = newTsNs - prevTsNs
        return delta in 1 until DISCONTINUITY_NS
    }

    /**
     * Frame pacing: true when the output frame scheduled for [dueNs] should be drawn on the vsync at
     * [vsyncNs]. A frame is drawn on the vsync nearest to its due time, so 90 FPS on a 120 Hz panel
     * really produces 90 frames/s (a plain "time since last frame" gate would round it down to 60).
     */
    fun isDue(vsyncNs: Long, dueNs: Long, vsyncPeriodNs: Long): Boolean =
        vsyncNs + vsyncPeriodNs / 2 >= dueNs

    /** Due time of the output frame after the one that was due at [dueNs] and has just been drawn on [vsyncNs]. */
    fun nextDue(dueNs: Long, vsyncNs: Long, intervalNs: Long): Long {
        val next = dueNs + intervalNs
        // Fell far behind (pause, seek, GPU stall, target above the panel rate): re-anchor instead of bursting.
        return if (vsyncNs - next > 2 * intervalNs) vsyncNs + intervalNs else next
    }

    /**
     * The panel refresh rate (Hz) to ask the system for when playing at [targetFps]: an exact match
     * first, then the lowest integer multiple (45 -> 90, 60 -> 120), then the lowest rate above the
     * target, then the highest the panel has. Null when there is nothing to choose.
     */
    fun pickRefreshRate(modes: List<Float>, targetFps: Int): Float? {
        if (targetFps <= 0 || modes.isEmpty()) return null
        modes.filter { abs(it - targetFps) < 0.5f }.maxOrNull()?.let { return it }
        modes.filter { hz ->
            val ratio = hz / targetFps
            val multiple = ratio.roundToInt()
            multiple >= 1 && abs(ratio - multiple) < 0.02f
        }.minOrNull()?.let { return it }
        modes.filter { it >= targetFps }.minOrNull()?.let { return it }
        return modes.maxOrNull()
    }

    /**
     * Whether a panel with the given refresh [modes] (Hz) can show [targetFps] evenly, i.e. some mode is
     * an integer multiple of it (45 -> 45 or 90 Hz, 60 -> 60 or 120 Hz, ...).
     */
    fun isRateSupported(modes: List<Float>, targetFps: Int): Boolean {
        if (targetFps <= 0) return true
        return modes.any { hz ->
            val ratio = hz / targetFps
            val multiple = ratio.roundToInt()
            multiple >= 1 && abs(ratio - multiple) < 0.02f
        }
    }
}
