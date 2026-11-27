package com.beatraxus.app.fpsbooster

/** How the last statistics window judged the load; produced by [AdaptiveQualityController], consumed by [TargetGovernor]. */
enum class LoadVerdict {
    /** Plenty of headroom: estimate, draw, drops and shed frames all well inside their budgets. */
    CALM,
    /** Fine, but not calm enough to ask for more. */
    NORMAL,
    /** Over a budget (estimate, draw, drops, shed frames or skipped estimates). */
    OVERLOADED,
    /** Far over a budget. */
    SEVERE
}

/** Why the output target changed; the text is shown to the user next to the statistics. */
enum class TargetChangeReason(val text: String) {
    OVERLOAD("the device could not sustain it"),
    THERMAL("the device is getting too hot"),
    HEADROOM("the device has headroom")
}

data class TargetDecision(val target: Int, val changed: Boolean, val reason: TargetChangeReason?)

/**
 * Chooses the output frame rate at run time (pure Kotlin, unit tested on the JVM).
 *
 * [candidates] are the targets that are worth running AND that the panel shows evenly (the planner builds them); the governor
 * only ever moves between them, so the cadence stays exact. It does two things:
 *
 *  - Steps DOWN when the device cannot sustain the current target. The quality ladder is tried first: the target only drops once
 *    the ladder is at or below [STEP_DOWN_RUNG] (Minimal / Ultra Fast) and the load is still over budget, or when the heat
 *    forecast stays at "moderate" for a 90 / 120 target. A smooth 90 beats a stuttering 120, but a 120 at lower analysis
 *    resolution beats an early drop to 90 while quality can still pay for it.
 *  - Steps UP, gradually, when the device has proven headroom: [UP_AFTER] calm windows in a row, the ladder back at or above
 *    [UP_RUNG], no heat, and the measured draw time fits the NEXT target's frame budget. A step that fails soon after it was
 *    taken blocks that target for longer each time (30, 60, 120, 240 windows), so a phone that cannot hold a rate stops
 *    being sent back to it.
 *
 * [auto] = false is the manual mode: the target the user picked is the ceiling. It still steps down (never force a target
 * the device cannot sustain) but only after a longer run of failing windows, and it recovers up to the user's choice.
 */
class TargetGovernor(candidates: List<Int>, startTarget: Int, private val auto: Boolean) {
    private val list: List<Int> = candidates.filter { it > 0 }.distinct().sorted().ifEmpty { listOf(startTarget.coerceAtLeast(1)) }
    private var index: Int = list.indexOfLast { it <= startTarget }.coerceAtLeast(0)

    /** The target to present now. */
    val target: Int get() = list[index]
    /** The highest target this session may use: the user's choice in manual mode, the panel's best in auto mode. */
    val ceiling: Int get() = list.last()

    private var strain = 0
    private var heat = 0
    private var calm = 0
    private var sinceChange = COOLDOWN
    private var trial = 0
    private val blocked = IntArray(list.size)
    private val failures = IntArray(list.size)

    /**
     * @param rungIndex the quality ladder's current rung ([AdaptiveQualityController.index])
     * @param adaptive false when adaptive resolution is off: the ladder cannot help, so the target is the only lever
     * @param displayHz the panel rate the presenter measured (0 = unknown); the target never climbs above what it can show
     */
    fun onWindow(
        verdict: LoadVerdict, rungIndex: Int, adaptive: Boolean, thermal: ThermalLevel, w: WindowStats, displayHz: Float = w.displayHz
    ): TargetDecision {
        sinceChange++
        for (i in blocked.indices) if (blocked[i] > 0) blocked[i]--
        if (trial > 0 && --trial == 0 && verdict != LoadVerdict.OVERLOADED && verdict != LoadVerdict.SEVERE) failures[index] = 0

        val atFloor = !adaptive || rungIndex >= STEP_DOWN_RUNG
        val struggling = (verdict == LoadVerdict.OVERLOADED || verdict == LoadVerdict.SEVERE) && atFloor
        val hot = thermal.ordinal >= ThermalLevel.MODERATE.ordinal && TargetBudget.isHighRate(target.toFloat())
        strain = if (struggling) strain + 1 else 0
        heat = if (hot) heat + 1 else 0
        calm = if (verdict == LoadVerdict.CALM && thermal == ThermalLevel.NONE) calm + 1 else 0

        val needed = when {
            verdict == LoadVerdict.SEVERE -> if (auto) 1 else SEVERE_MANUAL_DOWN_AFTER
            auto -> DOWN_AFTER
            else -> MANUAL_DOWN_AFTER
        }
        if (index > 0 && sinceChange >= COOLDOWN && ((struggling && strain >= needed) || heat >= THERMAL_DOWN_AFTER)) {
            val fromThermal = !(struggling && strain >= needed)
            val old = index
            failures[old]++
            blocked[old] = UP_BLOCK shl (failures[old] - 1).coerceIn(0, MAX_BLOCK_DOUBLINGS)
            index--
            return changed(if (fromThermal) TargetChangeReason.THERMAL else TargetChangeReason.OVERLOAD)
        }

        if (index < list.lastIndex && sinceChange >= COOLDOWN && calm >= UP_AFTER && (!adaptive || rungIndex <= UP_RUNG)) {
            val next = list[index + 1]
            val presentable = displayHz <= 0f || next <= displayHz + 0.5f
            val fits = w.avgDrawMs <= TargetBudget.periodMs(next.toFloat()) * TargetBudget.DRAW_SHARE * UP_PROBE_MARGIN
            if (blocked[index + 1] == 0 && presentable && fits) {
                index++
                trial = TRIAL_WINDOWS
                return changed(TargetChangeReason.HEADROOM)
            }
        }
        return TargetDecision(target, false, null)
    }

    private fun changed(reason: TargetChangeReason): TargetDecision {
        strain = 0; heat = 0; calm = 0; sinceChange = 0
        return TargetDecision(target, true, reason)
    }

    companion object {
        /**
         * The governor for a plan. The plan's own target is always one of the candidates (a plan copied with a new target but old
         * candidates must still run at that target). Auto starts where this phone last settled ([learned]) or at the plan's
         * target and adapts to the load; a manual choice stays exactly where the user put it.
         */
        fun forPlan(plan: BoosterPlan, learned: Int?): TargetGovernor {
            // A target the user picked is theirs: it is pinned (one candidate), so neither a remembered "this phone could not
            // sustain it" nor a run-time overload steps it down. The picture quality ladder still protects the phone; only the
            // output rate is left alone. Auto is the adaptive mode and starts where this phone last settled.
            if (!plan.auto) return TargetGovernor(listOf(plan.targetFps), plan.targetFps, auto = false)
            val start = learned ?: plan.targetFps
            return TargetGovernor((plan.candidates + plan.targetFps).distinct().sorted(), start, auto = true)
        }

        /** Windows between two target changes. */
        const val COOLDOWN = 3
        /** Consecutive windows over budget (with the ladder at its floor) before the target drops: auto / manual. */
        const val DOWN_AFTER = 2
        const val MANUAL_DOWN_AFTER = 5
        const val SEVERE_MANUAL_DOWN_AFTER = 2
        /** Consecutive "moderate or worse" heat windows before a 90 / 120 target drops. */
        const val THERMAL_DOWN_AFTER = 3
        /** Quality-ladder rung from which the target drops instead of the picture getting even softer (Minimal). */
        const val STEP_DOWN_RUNG = 3
        /** Rung the ladder must be back at (Performance or better) before a higher target is tried. */
        const val UP_RUNG = 2
        /** Calm windows in a row before a higher target is tried. */
        const val UP_AFTER = 15
        /**
         * A higher target is only tried when the measured draw is within this multiple of ITS draw budget. It is above 1 on
         * purpose: at the current, lighter target the ladder sits on a high rung, and the draw time includes the contention of
         * that heavy motion estimate, which the ladder gives back once the higher target is running. Hopeless targets (a draw
         * far above even that) are still never tried, and a failed try is backed off (see [UP_BLOCK]).
         */
        const val UP_PROBE_MARGIN = 1.5f
        const val UP_BLOCK = 30
        const val MAX_BLOCK_DOUBLINGS = 3
        /** Windows a freshly raised target must hold before it counts as sustained (resets its failure count). */
        const val TRIAL_WINDOWS = 20

        /**
         * Targets worth offering for a session: those up to [ceiling] that the panel shows evenly and that are far enough
         * above the source ([auto] needs 1.2x; manual only avoids a source already near the target). Ascending.
         */
        fun candidates(ceiling: Int, panelRates: List<Float>, sourceFps: Float, auto: Boolean): List<Int> =
            BoosterTargets.ALL.filter { t ->
                t <= ceiling && com.beatraxus.app.motionboost.MotionBoostTiming.isCleanRate(panelRates, t) &&
                    if (auto) sourceFps <= 0f || t > sourceFps * 1.2f
                    else !com.beatraxus.app.motionboost.MotionBoostTiming.shouldBypass(sourceFps, t)
            }.sorted()
    }
}
