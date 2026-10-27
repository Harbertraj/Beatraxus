package com.beatraxus.app.fpsbooster

/** Android thermal status (PowerManager.THERMAL_STATUS_*), reduced to what the controller needs. */
enum class ThermalLevel {
    NONE, LIGHT, MODERATE, SEVERE;

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
 *  - The ladder is QUALITY > BALANCED > PERFORMANCE > MINIMAL. It starts at the user's preset and never goes
 *    above it. Battery saver starts (and stays capped) at PERFORMANCE.
 *  - A window is *overloaded* when the motion estimate takes longer than the budget (a share of the source
 *    frame interval), the presenter dropped more than 5 % of its output frames, or more than 5 % of the source
 *    frames could not get a motion estimate because the GPU was still busy with the previous one (those pairs
 *    are shown as real frames; with the estimate measured on every pair this is the clearest sign that a preset
 *    is too heavy for the device).
 *  - Overload steps down one rung (at most once per [COOLDOWN] windows) when adaptive resolution is on. The
 *    booster is NEVER switched off because of load: at the lowest rung (or with adaptive resolution off) it
 *    keeps running and the presenter simply drops what it cannot produce. Only a severe thermal state stops it.
 *  - Sustained headroom ([UPGRADE_AFTER] calm windows, thermals normal, battery saver off) steps back up;
 *    an overload shortly after an upgrade blocks further upgrades for a while (no oscillation).
 *  - Moderate thermal status forces at least one step down and blocks upgrades; severe status falls back at once.
 */
class AdaptiveQualityController(
    userQuality: BoosterQuality,
    private val adaptive: Boolean,
    private val batterySaver: Boolean
) {
    companion object {
        const val COOLDOWN = 2
        const val UPGRADE_AFTER = 10
        const val UPGRADE_BLOCK = 30
        const val BUDGET_SHARE = 0.7f
        const val DROP_RATIO = 0.05f
        /** Share of source frames whose motion estimate was skipped (GPU still busy) above which a window is overloaded. */
        const val SKIP_RATIO = 0.05f

        val LADDER: List<QualityLevel> = listOf(
            QualityLevel("Quality", 1280, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, true),
            QualityLevel("Balanced", 960, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, true),
            QualityLevel("Performance", 640, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, false),
            // Only reachable with adaptive resolution on. Only the motion-search size shrinks: the shown frame stays at
            // the video's own resolution (a 1280 px frame cap here made 1080p / 4K video visibly softer).
            QualityLevel("Minimal", 480, FRAME_EDGE_ORIGINAL, FlowPyramid.COARSEST_RANGE, false)
        )

        fun indexFor(q: BoosterQuality) = when (q) {
            BoosterQuality.QUALITY -> 0
            BoosterQuality.BALANCED -> 1
            BoosterQuality.PERFORMANCE -> 2
        }
    }

    /** Best rung this session may use. */
    private val bestIndex: Int = if (batterySaver) maxOf(2, indexFor(userQuality)) else indexFor(userQuality)
    var index: Int = bestIndex
        private set
    val level: QualityLevel get() = LADDER[index]

    private var calmStreak = 0
    private var sinceChange = COOLDOWN
    private var upgradeBlock = 0
    private var lastChangeWasUpgrade = false
    private var windowsSinceUpgrade = Int.MAX_VALUE

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
        val overloaded = w.avgEstimateMs > budgetMs || dropRatio > DROP_RATIO || missRatio > 0.15f || skipRatio > SKIP_RATIO
        val calm = !overloaded && w.avgEstimateMs < budgetMs * 0.45f && dropRatio == 0f && missRatio < 0.03f &&
            w.skippedEstimates == 0

        calmStreak = if (calm) calmStreak + 1 else 0

        var changed = false
        val before = index

        // Thermal: moderate forces a step down and prevents climbing back.
        if (thermal == ThermalLevel.MODERATE && index < LADDER.lastIndex && index == bestIndex && adaptive) {
            index++
        }

        if (overloaded && adaptive && index < LADDER.lastIndex && sinceChange >= COOLDOWN && index == before) {
            index++
            if (lastChangeWasUpgrade && windowsSinceUpgrade <= 3) upgradeBlock = UPGRADE_BLOCK
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
