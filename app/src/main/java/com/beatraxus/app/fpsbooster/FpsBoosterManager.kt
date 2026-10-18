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
    private val plan: BoosterPlan,
    settings: FpsBoosterSettings,
    private val panelRates: List<Float>,
    private val listener: Listener
) {
    interface Listener {
        fun onQualityLevel(level: QualityLevel)
        fun onStats(stats: BoosterStats)
        fun onFallback(reason: FallbackReason)
    }

    private val appContext = context.applicationContext
    private val controller = AdaptiveQualityController(plan.quality, settings.adaptiveResolution, settings.batterySaver)
    private var thermal = ThermalLevel.NONE
    private var droppedTotal = 0L
    private var finished = false
    /**
     * Windows to skip after anything that disturbs the pipeline (new session, target change, quality step).
     * Reallocating GPU resources and re-pacing output make the next one or two windows look overloaded even
     * on a healthy device; judging them used to trigger "could not keep up" right after a setting change.
     */
    private var graceWindows = START_GRACE_WINDOWS
    private val main = Handler(Looper.getMainLooper())

    private val power: PowerManager? = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** Rung the renderer should start with. */
    val initialLevel: QualityLevel get() = controller.level

    init {
        val pm = power
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            runCatching {
                val l = PowerManager.OnThermalStatusChangedListener { status -> thermal = ThermalLevel.fromAndroidStatus(status) }
                thermal = ThermalLevel.fromAndroidStatus(pm.currentThermalStatus)
                pm.addThermalStatusListener({ r -> main.post(r) }, l)
                thermalListener = l
            }.onFailure { Log.w(TAG, "thermal listener unavailable", it) }
        }
    }

    /** One finished statistics window from the renderer (already posted to the main thread). */
    fun onWindow(w: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {
        if (finished) return
        droppedTotal += w.dropped
        // Pause, seek or a stalled source produce almost no frames: that says nothing about load.
        var evaluate = w.sourceFrames >= 3
        if (graceWindows > 0) { graceWindows--; evaluate = false }
        var fallback: FallbackReason? = null
        if (evaluate) {
            // The presenter can never show more frames than the panel is refreshing right now (the system may be
            // running 60 Hz while the target is 120): that is not GPU overload and must not be judged as such.
            val expected = minOf(
                FramePacing.achievableFps(plan.targetFps, panelRates).toFloat(),
                DisplayRefreshRate.currentRate(appContext)
            )
            val d = controller.onWindow(w, sourceFps, expected, thermal)
            if (d.changed) { graceWindows = CHANGE_GRACE_WINDOWS; listener.onQualityLevel(d.level) }
            fallback = d.fallback
        }
        listener.onStats(
            BoosterStats(
                running = fallback == null,
                engine = BoosterEngine.OPTICAL_FLOW,
                sourceFps = sourceFps,
                targetFps = plan.targetFps,
                renderedFps = w.renderedFps,
                generatedFps = w.generatedFps,
                droppedFrames = droppedTotal,
                estimateLatencyMs = w.avgEstimateMs,
                drawMs = w.avgDrawMs,
                addedDelayMs = addedDelayMs,
                workEdge = workEdge,
                qualityLabel = controller.level.label,
                fallbackReason = fallback?.message
            )
        )
        if (fallback != null) {
            finished = true
            listener.onFallback(fallback)
        }
    }

    /** Call after a settings change that re-paces or reallocates the pipeline without creating a new manager. */
    fun restartGrace() { graceWindows = START_GRACE_WINDOWS }

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
    }
}
