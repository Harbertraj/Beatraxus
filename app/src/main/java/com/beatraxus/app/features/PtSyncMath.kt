package com.beatraxus.app.features

import kotlin.math.abs

/**
 * Pure timing rules for keeping a Play Together follower on the controller's position
 * (no Android classes, so they are unit tested on the JVM).
 */
internal object PtSyncMath {

    /** Position errors up to this are left alone (below what the ear can place, and inside clock jitter). */
    const val DEADBAND_MS = 6L

    /** Errors up to this are fixed by trimming / padding a few ms of audio instead of seeking. */
    const val SOFT_MAX_MS = 250L

    /** A start that is at most this late is pressed at once; later than that the player is repositioned first. */
    const val LATE_OK_MS = 30L

    /** More than this early means the room position moved (e.g. the controller seeked back): reposition. */
    const val MAX_WAIT_MS = 1_500L

    enum class Fix { NONE, NUDGE, SEEK }

    enum class GateStep { WAIT_THEN_START, START_NOW, RESEEK }

    /** [errMs] = room position - this phone's position (positive: this phone is late). */
    fun fixFor(errMs: Long): Fix {
        val a = abs(errMs)
        return when {
            a <= DEADBAND_MS -> Fix.NONE
            a <= SOFT_MAX_MS -> Fix.NUDGE
            else -> Fix.SEEK
        }
    }

    /**
     * How long to wait before pressing play so that the first audible sample is at the room position.
     * [startAtPosMs] is where the player is parked; [audioLatencyMs] is how long play() takes to become audible.
     * Positive: too early, wait that long. Negative: already late by that much.
     */
    fun gateWaitMs(startAtPosMs: Long, roomPosMs: Long, audioLatencyMs: Long): Long =
        startAtPosMs - (roomPosMs + audioLatencyMs)

    fun gateStep(waitMs: Long): GateStep = when {
        waitMs > MAX_WAIT_MS -> GateStep.RESEEK
        waitMs > 2 -> GateStep.WAIT_THEN_START
        waitMs >= -LATE_OK_MS -> GateStep.START_NOW
        else -> GateStep.RESEEK
    }

    /** Where to park the player so that, once it is ready after [readyGuessMs], the room clock is right there. */
    fun parkPosition(roomPosMs: Long, readyGuessMs: Long, audioLatencyMs: Long): Long =
        roomPosMs + readyGuessMs + audioLatencyMs

    /**
     * How much audio may be skipped (late phone) or padded with silence (early phone) in one step.
     * Skipping can only use audio that is already decoded ([bufferedMs]).
     */
    fun nudgeLimitMs(driftMs: Long, bufferedMs: Long, maxPadMs: Long = SOFT_MAX_MS): Long = when {
        driftMs > 0 -> minOf(driftMs, (bufferedMs * 8) / 10)
        driftMs < 0 -> maxOf(driftMs, -maxPadMs)
        else -> 0L
    }

    /** Exponential moving average of a learned time (weight in 0..1 for the new sample), kept inside [lo]..[hi]. */
    fun learn(old: Long, sample: Long, weight: Double, lo: Long, hi: Long): Long =
        (old + (sample - old) * weight).toLong().coerceIn(lo, hi)

    /**
     * Clock offset smoothing: the first measurement is taken as is, later ones are blended in, and a
     * measurement with a long round trip (less trustworthy) counts for less.
     */
    fun blendClockOffset(old: Long?, sample: Long, rttMs: Long): Long {
        if (old == null) return sample
        val weight = when {
            rttMs <= 60 -> 0.5
            rttMs <= 150 -> 0.3
            rttMs <= 400 -> 0.15
            else -> 0.05
        }
        return (old + (sample - old) * weight).toLong()
    }
}
