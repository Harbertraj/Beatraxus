package com.beatraxus.app.engine

import kotlin.math.abs

/**
 * Slew-limited follower for the automatic A/V compensation (pure Kotlin, unit tested on the JVM).
 *
 * The FPS Booster holds every picture back by a few tens of milliseconds so the next source frame (and its motion
 * estimate) is ready. The sound is not held, so without compensation the lips lead the picture by that hold. The audio
 * clock the player syncs to is reported that much ahead (see [AudioDelayRenderer]), which makes the player decode and
 * release frames earlier by exactly the hold.
 *
 * A step in the reported clock would make the player drop or repeat a frame, so the compensation is followed, never
 * jumped to: it moves by at most [MAX_SLEW] milliseconds per millisecond of wall time (2 %: video speeds up or slows
 * down by 2 % while it settles, which is not visible, and a 80 ms hold is reached in 4 s).
 */
class AutoDelayRamp {
    private var currentMs = 0.0
    private var lastNs = 0L

    /** Compensation (ms) applied right now. */
    val valueMs: Double get() = currentMs

    /** Moves towards [targetMs] for the wall time elapsed since the previous call, and returns the new value. */
    fun update(targetMs: Int, nowNs: Long): Double {
        val clamped = targetMs.coerceIn(0, MAX_MS)
        if (lastNs == 0L) {
            // First call of a run: nothing was shown yet, so there is nothing to slew.
            lastNs = nowNs
            currentMs = clamped.toDouble()
            return currentMs
        }
        val dtMs = ((nowNs - lastNs) / 1e6).coerceIn(0.0, MAX_STEP_MS)
        lastNs = nowNs
        val diff = clamped - currentMs
        val maxMove = dtMs * MAX_SLEW
        currentMs += if (abs(diff) <= maxMove) diff else if (diff > 0) maxMove else -maxMove
        return currentMs
    }

    fun reset() { currentMs = 0.0; lastNs = 0L }

    companion object {
        /** The hold never exceeds this (MotionBoostTiming caps it at 150 ms); it is also the cap of the target. */
        const val MAX_MS = 200
        const val MAX_SLEW = 0.02
        /** One call never integrates more than this much wall time (a pause or a stall must not give a big jump later). */
        const val MAX_STEP_MS = 250.0
    }
}
