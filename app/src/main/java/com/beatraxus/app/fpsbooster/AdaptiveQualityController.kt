package com.beatraxus.app.fpsbooster

/** Android thermal status (PowerManager.THERMAL_STATUS_*), reduced to what the controller needs. */
enum class ThermalLevel(val label: String) {
    NONE("None"), LIGHT("Light"), MODERATE("Moderate"), SEVERE("Severe");

    companion object {
        /** 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
        fun fromAndroidStatus(status: Int): ThermalLevel = when {
            status <= 0 -> NONE
            status == 1 -> LIGHT
            status == 2 -> MODERATE
            else -> SEVERE
        }
    }
}

/** Why the booster stopped and standard playback took over. */
enum class FallbackReason(val message: String) {
    /** No longer produced by [AdaptiveQualityController]: the booster keeps running when the device is loaded. */
    CANNOT_KEEP_UP("Device could not keep up: playing normally. Try a lower Quality or turn on Adaptive resolution"),
    THERMAL("Device is too hot: playing normally"),
    GPU_ERROR("FPS Booster could not start (GPU error): playing normally")
    // There is no "RIFE too slow" reason any more: RIFE lowers its own model size on the quality ladder and keeps running.
}

/** One rung of the quality ladder: the processing sizes the renderer should use. */
data class QualityLevel(
    val label: String,
    val workEdge: Int,
    val frameEdge: Int,
    val searchRange: Int,
    val subpixel: Boolean
)

/** Result of evaluating one statistics window. */
data class QualityDecision(val level: QualityLevel, val changed: Boolean, val fallback: FallbackReason?)

/**
 * Picks the processing resolution from live measurements. Pure state machine (no Android, no clocks) so the
 * policy is unit tested; the manager feeds it one [WindowStats] per second.
 *
 * Policy:
 *  - The ladder is QUALITY > BALANCED > PERFORMANCE > MINIMAL > ULTRA FAST. It starts at the user's preset and never goes
 *    above it. Battery saver starts (and stays capped) at PERFORMANCE.
 *  - A window is *overloaded* when the motion estimate takes longer than the budget (a share of the source
 *    frame interval), the presenter dropped more than 5 % of its output frames, or more than 5 % of the source
 *    frames could not get a motion estimate because the GPU was still busy with the previous one (those pairs
 *    are shown as real frames; with the estimate measured on every pair this is the clearest sign that a preset
 *    is too heavy for the device).
 *  - For a target above 60 FPS a window is also overloaded when the average draw + swap exceeds [TargetBudget.DRAW_SHARE]
 *    of one output period (severe above the whole period), and upgrades need the draw well inside that budget.
 *  - A window where more than [SHED_RATIO] of the frames had to be shown cheaply (deadline policy) is overloaded too.
 *  - Overload steps down one rung (at most once per [COOLDOWN] windows; every window for 90 / 120 FPS targets) when adaptive resolution is on. The
 *    booster is NEVER switched off because of load: at the lowest rung (or with adaptive resolution off) it
 *    keeps running and the presenter simply drops what it cannot produce. Only a severe thermal state stops it.
 *  - Sustained headroom ([UPGRADE_AFTER] calm windows, thermals normal, battery saver off) steps back up;
 *    an overload shortly after an upgrade blocks further upgrades for a while (no oscillation).
 *  - Moderate thermal status forces at least one step down and blocks upgrades; severe status falls back at once.
 */
class AdaptiveQualityController(
    userQuality: BoosterQuality,
    private val adaptive: Boolean,
    private val batterySaver: Boolean,
    /** Rung the last session on this phone settled on (see DeviceTuning); never above the preset, ignored when not adaptive. */
    startIndex: Int? = null
) {
    companion object {
        const val COOLDOWN = 2
        const val UPGRADE_AFTER = 10
        const val UPGRADE_BLOCK = 30
        /** Every failed upgrade doubles the block (30, 60, 120, 240 windows): a phone that cannot hold a rung stops being sent back to it. */
        const val MAX_BLOCK_DOUBLINGS = 3
        /** Windows an upgrade must hold without overload before it counts as successful (resets the doubling). */
        const val UPGRADE_HOLD = 20
        /** Share of source frames without a motion estimate above which the rung is far too heavy: step down two at once. */
        const val SEVERE_SKIP_RATIO = 0.5f
        /** Emergency fallback when a single frame-pair estimate exceeds the source-frame interval by 25%. */
        const val SEVERE_LATENCY_MULTIPLIER = 1.25f
        const val SEVERE_DROP_RATIO = 0.20f
        const val SEVERE_MISS_RATIO = 0.40f
        const val BUDGET_SHARE = 0.7f
        const val DROP_RATIO = 0.05f
        /** Share of source frames whose motion estimate was skipped (GPU still busy) above which a window is overloaded. */
        const val SKIP_RATIO = 0.05f
        /** Share of presented frames that the deadline policy had to show as cheap real frames (see [FrameDeadlinePolicy]). */
        const val SHED_RATIO = 0.10f
        const val SEVERE_SHED_RATIO = 0.30f
        /** High-rate targets (90 / 120) re-evaluate after every window: one second at 11 / 8 ms frames is a lot of lost frames. */
        const val HIGH_RATE_COOLDOWN = 1

        val LADDER: List<QualityLevel> = listOf(
            QualityLevel("Quality", 1280, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, true),
            QualityLevel("Balanced", 960, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, true),
            QualityLevel("Performance", 640, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, false),
            // Only reachable with adaptive resolution on. Only the motion-search size shrinks: the shown frame stays at
            // the video's own resolution (a 1280 px frame cap here made 1080p / 4K video visibly softer).
            QualityLevel("Minimal", 480, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, false),
            // Last resort, only reachable with adaptive resolution on (and appended, so saved rung indices stay valid).
            // Reached when even Minimal cannot hold a 90 / 120 FPS output frame budget.
            QualityLevel("Ultra Fast", 320, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, false)
        )

        fun indexFor(q: BoosterQuality) = when (q) {
            BoosterQuality.QUALITY -> 0
            BoosterQuality.BALANCED -> 1
            BoosterQuality.PERFORMANCE -> 2
        }
    }

    /** Best rung this session may use. */
    private val bestIndex: Int = if (batterySaver) maxOf(2, indexFor(userQuality)) else indexFor(userQuality)
    var index: Int = if (adaptive && startIndex != null) startIndex.coerceIn(bestIndex, LADDER.lastIndex) else bestIndex
        private set
    val level: QualityLevel get() = LADDER[index]

    private var calmStreak = 0
    private var sinceChange = COOLDOWN
    private var upgradeBlock = 0
    private var lastChangeWasUpgrade = false
    private var windowsSinceUpgrade = Int.MAX_VALUE
    private var failedUpgrades = 0

    /**
     * @param sourceFps  measured source frame rate (falls back to 24 when unknown)
     * @param expectedPresentFps rate the presenter should reach (target, limited by the panel)
     */
    fun onWindow(w: WindowStats, sourceFps: Float, expectedPresentFps: Float, thermal: ThermalLevel): QualityDecision {
        sinceChange++
        if (upgradeBlock > 0) upgradeBlock--
        if (windowsSinceUpgrade != Int.MAX_VALUE) windowsSinceUpgrade++

        if (thermal == ThermalLevel.SEVERE) return QualityDecision(level, false, FallbackReason.THERMAL)

        val interval = 1000f / (if (sourceFps > 1f) sourceFps else 24f)
        val budgetMs = interval * BUDGET_SHARE
        val total = w.presented + w.dropped
        val dropRatio = if (total > 0) w.dropped.toFloat() / total else 0f
        val missed = FramePacing.missedOutputs(expectedPresentFps, w.durationNs, w.presented)
        val missRatio = if (expectedPresentFps > 0f) missed / (expectedPresentFps * w.durationNs / 1e9f) else 0f
        val skipRatio = if (w.sourceFrames > 0) w.skippedEstimates.toFloat() / w.sourceFrames else 0f
        // High-rate targets (90 / 120) have an output-frame budget of 11 / 8 ms. The draw + swap of every output frame has
        // to fit in it, which the per-source-frame estimate budget above does not see. 60 FPS and below keep the policy
        // they always had (drawBudget = 0 disables these terms).
        val drawBudgetMs = TargetBudget.drawBudgetMs(expectedPresentFps)
        val drawOver = drawBudgetMs > 0f && w.avgDrawMs > drawBudgetMs
        val drawSevere = drawBudgetMs > 0f && w.avgDrawMs > TargetBudget.periodMs(expectedPresentFps)
        val drawCalm = drawBudgetMs == 0f || w.avgDrawMs < drawBudgetMs * 0.6f
        val shedRatio = if (w.presented > 0) w.shed.toFloat() / w.presented else 0f
        val highRate = TargetBudget.isHighRate(expectedPresentFps)
        val cooldown = if (highRate) HIGH_RATE_COOLDOWN else COOLDOWN
        val overloaded = w.avgEstimateMs > budgetMs || dropRatio > DROP_RATIO || missRatio > 0.15f || skipRatio > SKIP_RATIO ||
            drawOver || shedRatio > SHED_RATIO
        val severeOverload = w.avgEstimateMs > interval * SEVERE_LATENCY_MULTIPLIER ||
            skipRatio > SEVERE_SKIP_RATIO ||
            dropRatio > SEVERE_DROP_RATIO ||
            missRatio > SEVERE_MISS_RATIO ||
            drawSevere || shedRatio > SEVERE_SHED_RATIO
        val calm = !overloaded && w.avgEstimateMs < budgetMs * 0.45f && dropRatio == 0f && missRatio < 0.03f &&
            w.skippedEstimates == 0 && drawCalm && w.shed == 0

        calmStreak = if (calm) calmStreak + 1 else 0

        var changed = false
        val before = index

        // Thermal: moderate forces a step down and prevents climbing back.
        if (thermal == ThermalLevel.MODERATE && index < LADDER.lastIndex && index == bestIndex && adaptive) {
            index++
        }

        if (!overloaded && lastChangeWasUpgrade && windowsSinceUpgrade != Int.MAX_VALUE && windowsSinceUpgrade >= UPGRADE_HOLD) failedUpgrades = 0

        if (severeOverload && index < LADDER.lastIndex && sinceChange >= cooldown && index == before) {
            // A device that cannot finish even one source-frame pair in time needs an immediate
            // quality reduction, regardless of the optional adaptive-resolution preference.
            // A 90 / 120 FPS target that is over its whole frame period (draw or shed share) is just as far off: two rungs.
            val steps = if (skipRatio > SEVERE_SKIP_RATIO ||
                w.avgEstimateMs > interval * SEVERE_LATENCY_MULTIPLIER ||
                (highRate && (drawSevere || shedRatio > SEVERE_SHED_RATIO))) 2 else 1
            index = (index + steps).coerceAtMost(LADDER.lastIndex)
            calmStreak = 0
            lastChangeWasUpgrade = false
            upgradeBlock = maxOf(upgradeBlock, UPGRADE_BLOCK)
        } else if (overloaded && adaptive && index < LADDER.lastIndex && sinceChange >= cooldown && index == before) {
            index++
            // Most pairs got no motion estimate at all: one rung is not going to be enough, so do not spend two more
            // seconds of stuttering finding that out.
            if (skipRatio > SEVERE_SKIP_RATIO && index < LADDER.lastIndex) index++
            if (lastChangeWasUpgrade && windowsSinceUpgrade <= 3) {
                upgradeBlock = UPGRADE_BLOCK shl failedUpgrades.coerceAtMost(MAX_BLOCK_DOUBLINGS)
                failedUpgrades++
            }
        } else if (calm && adaptive && index > bestIndex && calmStreak >= UPGRADE_AFTER &&
            upgradeBlock == 0 && thermal == ThermalLevel.NONE && !batterySaver
        ) {
            index--
            calmStreak = 0
            lastChangeWasUpgrade = true
            windowsSinceUpgrade = 0
        }

        if (index != before) {
            changed = true
            sinceChange = 0
            if (index > before) lastChangeWasUpgrade = false
        }

        return QualityDecision(level, changed, null)
    }
}
