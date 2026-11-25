package com.beatraxus.app.fpsbooster

/**
 * Deadline-aware frame generation for the presenter (pure Kotlin, unit tested on the JVM).
 *
 * At a 90 / 120 FPS target one output frame has 11 / 8 ms. When the presenter is already a whole vsync or more behind
 * the frame that was due ([lateNs]), a generated frame (the full warp draw) would push it further behind and the next
 * frames would miss too. In that case the policy asks for a CHEAP frame instead: the nearest real source frame, which
 * the draw path already supports (warp off). That lets the GPU queue drain and the cadence catch up.
 *
 * Nothing is faked: a shed frame is a real source frame, so [FrameClassifier] counts it as original / repeated, never as
 * generated, and [shedTotal] is reported so the statistics show how often the deadline forced it.
 *
 * Guard rails:
 *  - only for targets above 60 FPS ([TargetBudget.isHighRate]); 60 FPS and below always generate, as before;
 *  - at most [MAX_CONSECUTIVE_SHED] frames in a row: sustained overload must surface to [AdaptiveQualityController]
 *    (through drops, late frames and the shed share), not be hidden by silently switching interpolation off;
 *  - once shedding, generation resumes only when the lateness is back below half a vsync (hysteresis, no flapping).
 */
class FrameDeadlinePolicy {
    private var shedding = false
    private var consecutive = 0

    /** Frames shown cheaply because the deadline was already missed, since the last [reset]. */
    var shedTotal: Long = 0
        private set

    fun reset() { shedding = false; consecutive = 0; shedTotal = 0 }

    /**
     * @param lateNs how long after its due time this frame is being produced (0 when on time)
     * @param vsyncPeriodNs the measured vsync period
     * @return true to generate (warp) this frame, false to show the nearest real frame instead
     */
    fun shouldGenerate(expectedFps: Float, lateNs: Long, vsyncPeriodNs: Long): Boolean {
        if (!TargetBudget.isHighRate(expectedFps) || vsyncPeriodNs <= 0L) { shedding = false; consecutive = 0; return true }
        if (shedding) {
            if (lateNs < vsyncPeriodNs / 2) shedding = false
        } else if (lateNs >= vsyncPeriodNs) {
            shedding = true
        }
        if (shedding) {
            if (consecutive < MAX_CONSECUTIVE_SHED) {
                consecutive++
                shedTotal++
                return false
            }
            // The run of cheap frames hit its cap: generate this one. If the presenter is still a vsync behind on the next
            // frame, a fresh run starts, so at worst one frame in MAX + 1 is generated and the overload stays visible.
            shedding = false
        }
        consecutive = 0
        return true
    }

    companion object {
        const val MAX_CONSECUTIVE_SHED = 4
    }
}

/**
 * Thermal state from the OS status AND its look-ahead headroom (pure Kotlin).
 *
 * Android only raises the status once throttling is already close. `PowerManager.getThermalHeadroom` forecasts it: 1.0 means
 * throttling is expected at that point, so values approaching 1.0 tell a sustained 90 / 120 FPS load to back off BEFORE
 * the clocks drop and the frame rate collapses. The forecast is mapped onto the same [ThermalLevel]s the quality controller
 * already understands: LIGHT blocks climbing back up, MODERATE forces a step down. It never maps to SEVERE (which stops the
 * booster): only the OS status can do that.
 *
 * High-rate targets use lower thresholds (they heat the GPU harder and have the least slack). Escalation is immediate;
 * relaxing needs [RELAX_READS] consecutive readings below the threshold minus [HYSTERESIS], so a headroom value hovering
 * around a threshold does not flip the policy every second.
 */
class ThermalTracker {
    /** The level the controller should use now. */
    var level: ThermalLevel = ThermalLevel.NONE
        private set
    private var relaxStreak = 0

    fun reset() { level = ThermalLevel.NONE; relaxStreak = 0 }

    /**
     * @param status OS thermal status already mapped to a [ThermalLevel]
     * @param headroom forecast from `getThermalHeadroom`: 0..1+ (1.0 = throttling expected); NaN / negative = unavailable
     */
    fun update(status: ThermalLevel, headroom: Float, highRate: Boolean): ThermalLevel {
        val fromHeadroom = levelFor(headroom, highRate, level)
        val target = if (fromHeadroom.ordinal > status.ordinal) fromHeadroom else status
        if (target.ordinal >= level.ordinal) {
            level = target; relaxStreak = 0
        } else if (++relaxStreak >= RELAX_READS) {
            level = ThermalLevel.entries[level.ordinal - 1]   // one level at a time
            relaxStreak = 0
        }
        return level
    }

    companion object {
        const val RELAX_READS = 3
        const val HYSTERESIS = 0.05f
        const val LIGHT_AT = 0.80f
        const val MODERATE_AT = 0.92f
        /** High-rate targets react earlier. */
        const val HIGH_RATE_LIGHT_AT = 0.70f
        const val HIGH_RATE_MODERATE_AT = 0.85f

        /** Level a forecast alone asks for; [current] widens the thresholds downwards while already at that level (hysteresis). */
        fun levelFor(headroom: Float, highRate: Boolean, current: ThermalLevel = ThermalLevel.NONE): ThermalLevel {
            if (!headroom.isFinite() || headroom < 0f) return ThermalLevel.NONE
            val light = if (highRate) HIGH_RATE_LIGHT_AT else LIGHT_AT
            val moderate = if (highRate) HIGH_RATE_MODERATE_AT else MODERATE_AT
            val m = if (current.ordinal >= ThermalLevel.MODERATE.ordinal) moderate - HYSTERESIS else moderate
            val l = if (current.ordinal >= ThermalLevel.LIGHT.ordinal) light - HYSTERESIS else light
            return when {
                headroom >= m -> ThermalLevel.MODERATE
                headroom >= l -> ThermalLevel.LIGHT
                else -> ThermalLevel.NONE
            }
        }
    }
}
