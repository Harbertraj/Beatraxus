package com.beatraxus.app.fpsbooster

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

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

    private val controller = AdaptiveQualityController(plan.quality, settings.adaptiveResolution, settings.batterySaver)
    private var thermal = ThermalLevel.NONE
    private var droppedTotal = 0L
    private var finished = false
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
        val evaluate = w.sourceFrames >= 3
        var fallback: FallbackReason? = null
        if (evaluate) {
            val expected = FramePacing.achievableFps(plan.targetFps, panelRates).toFloat()
            val d = controller.onWindow(w, sourceFps, expected, thermal)
            if (d.changed) listener.onQualityLevel(d.level)
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

    fun release() {
        finished = true
        val pm = power
        val l = thermalListener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null && l != null) {
            runCatching { pm.removeThermalStatusListener(l) }
        }
        thermalListener = null
    }

    private companion object { const val TAG = "FpsBoosterMgr" }
}
