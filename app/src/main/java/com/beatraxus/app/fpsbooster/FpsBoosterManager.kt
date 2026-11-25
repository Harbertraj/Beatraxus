package com.beatraxus.app.fpsbooster

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.beatraxus.app.motionboost.DisplayRefreshRate

/**
 * Lifecycle + policy glue for one running booster session. Main thread only.
 *
 * Owns the [AdaptiveQualityController], listens for device thermal status, turns each statistics window from
 * the GL thread into (a) a [QualityLevel] decision for the renderer and (b) a [BoosterStats] snapshot for the UI,
 * and reports a [FallbackReason] when standard playback must take over.
 */
class FpsBoosterManager(
    context: Context,
    @Volatile private var plan: BoosterPlan,
    settings: FpsBoosterSettings,
    private val panelRates: List<Float>,
    private val listener: Listener
) {
    interface Listener {
        fun onQualityLevel(level: QualityLevel)
        fun onStats(stats: BoosterStats)
        fun onFallback(reason: FallbackReason)
    }

    private val appContext = runCatching { context.applicationContext }.getOrDefault(context)
    // Starts where the last session on this phone settled, so a heavy preset does not stutter through the same
    // step-downs on every video. It still climbs back up on its own when the phone has headroom.
    private val controller = AdaptiveQualityController(
        plan.quality, settings.adaptiveResolution, settings.batterySaver, DeviceTuning.learnedLevel(appContext, plan)
    )
    private var thermal = ThermalLevel.NONE
    private var droppedTotal = 0L
    private var finished = false
    /**
     * Windows to skip after anything that disturbs the pipeline (new session, target change, quality step).
     * Reallocating GPU resources and re-pacing output make the next one or two windows look overloaded even
     * on a healthy device; judging them used to trigger "could not keep up" right after a setting change.
     */
    private var graceWindows = START_GRACE_WINDOWS
    private val main = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()

    private val power: PowerManager? = runCatching {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }.getOrNull()
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    /** Look-ahead thermal forecast combined with the OS status (see [ThermalTracker]). */
    private val thermalTracker = ThermalTracker()
    private var thermalHeadroom = -1f
    private var thermalState = ThermalLevel.NONE
    private var missedTotal = 0L
    private var shedTotal = 0L

    /** Rung the renderer should start with. */
    val initialLevel: QualityLevel get() = controller.level

    init {
        val pm = power
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            runCatching {
                val l = PowerManager.OnThermalStatusChangedListener { status -> thermal = ThermalLevel.fromAndroidStatus(status) }
                thermal = ThermalLevel.fromAndroidStatus(pm.currentThermalStatus)
                pm.addThermalStatusListener({ r -> main?.post(r) }, l)
                thermalListener = l
            }.onFailure { Log.w(TAG, "thermal listener unavailable", it) }
        }
    }

    /** One finished statistics window from the renderer (already posted to the main thread). */
    fun onWindow(w: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {
        if (finished) return
        droppedTotal += w.dropped
        shedTotal += w.shed
        // Pause, seek or a stalled source produce almost no frames: that says nothing about load.
        var evaluate = w.sourceFrames >= 3
        if (graceWindows > 0) { graceWindows--; evaluate = false }
        var fallback: FallbackReason? = null
        if (evaluate) {
            // The presenter can never show more frames than the panel is refreshing right now (the system may be
            // running 60 Hz while the target is 120): that is not GPU overload and must not be judged as such.
            val currentRate = runCatching { DisplayRefreshRate.currentRate(appContext) }.getOrDefault(60f)
            // When the presenter measured its own cadence, that is the rate it is trying to show (it may be below the
            // target if the system did not grant the display mode): judge it against that, not against the target.
            val expected = if (w.cadenceFps > 0f) minOf(w.cadenceFps, plan.targetFps.toFloat()) else minOf(
                FramePacing.achievableFps(plan.targetFps, panelRates).toFloat(),
                currentRate
            )
            // Thermal: OS status plus the 10 s forecast (once per window, as the API asks). 90 / 120 react at lower headroom.
            thermalHeadroom = readThermalHeadroom()
            thermalState = thermalTracker.update(thermal, thermalHeadroom, TargetBudget.isHighRate(expected))
            missedTotal += FramePacing.missedOutputs(expected, w.durationNs, w.presented)
            val d = controller.onWindow(w, sourceFps, expected, thermalState)
            if (d.changed) {
                graceWindows = CHANGE_GRACE_WINDOWS
                DeviceTuning.rememberLevel(appContext, plan, controller.index)
                listener.onQualityLevel(d.level)
            }
            fallback = d.fallback
        }
        listener.onStats(
            BoosterStats(
                running = fallback == null,
                engine = plan.engine,
                sourceFps = sourceFps,
                targetFps = plan.targetFps,
                renderedFps = w.renderedFps,
                generatedFps = w.generatedFps,
                originalFps = w.originalFps,
                repeatedFps = w.repeatedFps,
                interpolationPct = w.interpolationPct,
                motionComplexity = MotionComplexity.fromOrdinal(w.motionComplexity).takeIf { it != MotionComplexity.UNKNOWN }?.label ?: "",
                droppedFrames = droppedTotal,
                missedVsyncs = missedTotal,
                deadlineShedFrames = shedTotal,
                thermalState = thermalState.label,
                thermalHeadroom = thermalHeadroom,
                estimateLatencyMs = w.avgEstimateMs,
                drawMs = w.avgDrawMs,
                addedDelayMs = addedDelayMs,
                workEdge = workEdge,
                qualityLabel = controller.level.label,
                fallbackReason = fallback?.message,
                cadenceVsyncs = w.cadenceVsyncs,
                displayHz = w.displayHz,
                cadenceExact = w.cadenceFps <= 0f || kotlin.math.abs(w.cadenceFps - plan.targetFps) < 0.5f
            )
        )
        if (fallback != null) {
            finished = true
            listener.onFallback(fallback)
        }
    }

    /** `getThermalHeadroom(10 s)` (API 30+); negative when the device does not report it. */
    private fun readThermalHeadroom(): Float {
        val pm = power ?: return -1f
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1f
        val h = runCatching { pm.getThermalHeadroom(THERMAL_FORECAST_S) }.getOrDefault(Float.NaN)
        return if (h.isFinite() && h >= 0f) h else -1f
    }

    /** Call after a settings change that re-paces or reallocates the pipeline without creating a new manager. */
    fun restartGrace() { graceWindows = START_GRACE_WINDOWS }

    /** Updates the plan (e.g. on target FPS change) and resets grace windows. */
    fun updatePlan(newPlan: BoosterPlan) {
        plan = newPlan
        restartGrace()
    }

    fun release() {
        finished = true
        val pm = power
        val l = thermalListener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null && l != null) {
            runCatching { pm.removeThermalStatusListener(l) }
        }
        thermalListener = null
    }

    private companion object {
        const val TAG = "FpsBoosterMgr"
        const val START_GRACE_WINDOWS = 3
        const val CHANGE_GRACE_WINDOWS = 2
        const val THERMAL_FORECAST_S = 10
    }
}
