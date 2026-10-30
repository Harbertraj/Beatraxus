package com.beatraxus.app.fpsbooster

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import com.beatraxus.app.model.DeinterlaceMode
import com.beatraxus.app.model.VideoOrientation
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.beatraxus.app.motionboost.DisplayRefreshRate

/**
 * Video output used while the optical-flow, RIFE AI, or HYBRID FPS Booster is running.
 * ExoPlayer is pointed at the renderer's input surface and the active renderer presents the (interpolated) frames here.
 */
class FpsBoosterSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    interface Listener {
        fun onStats(stats: BoosterStats)
        /** Main thread. The caller must switch back to standard playback. */
        fun onFallback(reason: FallbackReason, detail: String?)
    }

    var player: Player? = null
    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private var renderer: OpticalFlowRenderer? = null
    private var rifeRenderer: RifeRenderer? = null
    private var hybridRenderer: HybridRenderer? = null
    private var manager: FpsBoosterManager? = null
    private var inputSurface: Surface? = null
    private var plan: BoosterPlan? = null
    private var settings = FpsBoosterSettings()
    private var applied: Pair<BoosterPlan, FpsBoosterSettings>? = null
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var hue = 0f
    private var temperature = 0f
    private var orientation = VideoOrientation.NONE
    private var deinterlace = DeinterlaceMode.OFF
    private var aiFusion = false

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            renderer?.setSourceSize(videoSize.width, videoSize.height)
            rifeRenderer?.setSourceSize(videoSize.width, videoSize.height)
            hybridRenderer?.setSourceSize(videoSize.width, videoSize.height)
        }
    }

    init { holder.addCallback(this) }

    fun configure(plan: BoosterPlan, settings: FpsBoosterSettings) {
        val key = plan to settings
        if (key == applied) return
        // Engine switched while a renderer is live (OPTICAL_FLOW <-> AI_RIFE <-> HYBRID): the old renderer
        // must be fully torn down and a new one built for the new engine, otherwise the stale renderer
        // keeps the surface/ONNX session and the new one fights it.
        val engineChanged = applied != null && applied!!.first.engine != plan.engine
        if (engineChanged && holder.surface?.isValid == true &&
            (renderer != null || rifeRenderer != null || hybridRenderer != null)) {
            applied = key
            this.plan = plan
            this.settings = settings
            surfaceDestroyed(holder)
            surfaceCreated(holder)
            return
        }
        val needsNewSession = applied == null ||
            applied!!.first.quality != plan.quality ||
            applied!!.second.adaptiveResolution != settings.adaptiveResolution ||
            applied!!.second.batterySaver != settings.batterySaver
        val targetChanged = applied?.first?.targetFps != plan.targetFps
        this.plan = plan
        this.settings = settings
        applied = key
        renderer?.setTargetFps(plan.targetFps)
        rifeRenderer?.setTargetFps(plan.targetFps)
        hybridRenderer?.setTargetFps(plan.targetFps)
        applyFrameRateHint()
        applyDisplayMode()
        if (needsNewSession && (renderer != null || rifeRenderer != null || hybridRenderer != null)) {
            startManager()
            manager?.let { mgr ->
                renderer?.setLevel(mgr.initialLevel)
                hybridRenderer?.setLevel(mgr.initialLevel)
            }
        } else if (targetChanged) {
            manager?.updatePlan(plan)
        }
    }

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness; this.contrast = contrast; this.saturation = saturation
        renderer?.setColorGrade(brightness, contrast, saturation)
        rifeRenderer?.setColorGrade(brightness, contrast, saturation)
        hybridRenderer?.setColorGrade(brightness, contrast, saturation)
    }

    fun setColorTune(hueDegrees: Float, temperature: Float) {
        if (hueDegrees == hue && temperature == this.temperature) return
        hue = hueDegrees
        this.temperature = temperature
        renderer?.setColorTune(hueDegrees, temperature)
        rifeRenderer?.setColorTune(hueDegrees, temperature)
        hybridRenderer?.setColorTune(hueDegrees, temperature)
    }

    fun setOrientation(orientation: VideoOrientation) {
        this.orientation = orientation
        renderer?.setOrientation(orientation)
        rifeRenderer?.setOrientation(orientation)
        hybridRenderer?.setOrientation(orientation)
    }

    fun setDeinterlace(mode: DeinterlaceMode) {
        if (mode == deinterlace) return
        deinterlace = mode
        renderer?.setDeinterlace(mode.shaderValue)
        rifeRenderer?.setDeinterlace(mode.shaderValue)
        hybridRenderer?.setDeinterlace(mode.shaderValue)
    }

    fun setAiFusion(enabled: Boolean) {
        if (enabled == aiFusion) return
        aiFusion = enabled
        renderer?.setAiFusion(enabled)
        hybridRenderer?.setAiFusion(enabled)
    }

    private fun startManager() {
        manager?.release()
        val p = plan ?: return
        lateinit var created: FpsBoosterManager
        created = FpsBoosterManager(context, p, settings, DisplayRefreshRate.supportedRates(context), object : FpsBoosterManager.Listener {
            override fun onQualityLevel(level: QualityLevel) {
                if (manager === created) {
                    renderer?.setLevel(level)
                    hybridRenderer?.setLevel(level)
                }
            }
            override fun onStats(stats: BoosterStats) { if (manager === created) listener?.onStats(stats) }
            override fun onFallback(reason: FallbackReason) { if (manager === created) listener?.onFallback(reason, null) }
        })
        manager = created
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val p = plan ?: return
        if (manager == null) startManager()

        when (p.engine) {
            BoosterEngine.HYBRID -> {
                lateinit var createdHybrid: HybridRenderer
                createdHybrid = HybridRenderer(holder.surface, object : HybridRenderer.Callbacks {
                    override fun onInputSurfaceReady(surface: Surface) {
                        main.post { if (hybridRenderer === createdHybrid) { inputSurface = surface; player?.setVideoSurface(surface) } }
                    }

                    override fun onStats(stats: BoosterStats) {
                        main.post { if (hybridRenderer === createdHybrid) listener?.onStats(stats) }
                    }

                    override fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {
                        main.post { if (hybridRenderer === createdHybrid) manager?.onWindow(stats, sourceFps, workEdge, addedDelayMs) }
                    }

                    override fun onError(reason: FallbackReason, message: String) {
                        Log.e(TAG, "Hybrid FPS Booster failed: $message")
                        main.post { if (hybridRenderer === createdHybrid) listener?.onFallback(reason, message) }
                    }
                }, context.applicationContext, p.quality)
                hybridRenderer = createdHybrid
                createdHybrid.setTargetFps(p.targetFps)
                manager?.let { createdHybrid.setLevel(it.initialLevel) }
                createdHybrid.setColorGrade(brightness, contrast, saturation)
                createdHybrid.setColorTune(hue, temperature)
                createdHybrid.setOrientation(orientation)
                createdHybrid.setDeinterlace(deinterlace.shaderValue)
                if (aiFusion) createdHybrid.setAiFusion(true)
                player?.let { pl ->
                    pl.addListener(playerListener)
                    val size = pl.videoSize
                    if (size.width > 0 && size.height > 0) createdHybrid.setSourceSize(size.width, size.height)
                }
                createdHybrid.start()
            }
            BoosterEngine.AI_RIFE -> {
                lateinit var createdRife: RifeRenderer
                createdRife = RifeRenderer(holder.surface, object : RifeRenderer.Callbacks {
                    override fun onInputSurfaceReady(surface: Surface) {
                        main.post { if (rifeRenderer === createdRife) { inputSurface = surface; player?.setVideoSurface(surface) } }
                    }

                    override fun onStats(stats: BoosterStats) {
                        main.post { if (rifeRenderer === createdRife) listener?.onStats(stats) }
                    }

                    override fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {
                        main.post { if (rifeRenderer === createdRife) manager?.onWindow(stats, sourceFps, workEdge, addedDelayMs) }
                    }

                    override fun onError(reason: FallbackReason, message: String) {
                        Log.e(TAG, "RIFE AI Booster failed: $message")
                        main.post { if (rifeRenderer === createdRife) listener?.onFallback(reason, message) }
                    }
                }, context.applicationContext, p.quality)
                rifeRenderer = createdRife
                createdRife.setTargetFps(p.targetFps)
                createdRife.setColorGrade(brightness, contrast, saturation)
                createdRife.setColorTune(hue, temperature)
                createdRife.setOrientation(orientation)
                createdRife.setDeinterlace(deinterlace.shaderValue)
                player?.let { pl ->
                    pl.addListener(playerListener)
                    val size = pl.videoSize
                    if (size.width > 0 && size.height > 0) createdRife.setSourceSize(size.width, size.height)
                }
                createdRife.start()
            }
            else -> {
                lateinit var created: OpticalFlowRenderer
                created = OpticalFlowRenderer(holder.surface, object : OpticalFlowRenderer.Callbacks {
                    override fun onInputSurfaceReady(surface: Surface) {
                        main.post { if (renderer === created) { inputSurface = surface; player?.setVideoSurface(surface) } }
                    }

                    override fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {
                        main.post { if (renderer === created) manager?.onWindow(stats, sourceFps, workEdge, addedDelayMs) }
                    }

                    override fun onError(reason: FallbackReason, message: String) {
                        Log.e(TAG, "FPS Booster failed: $message")
                        main.post { if (renderer === created) listener?.onFallback(reason, message) }
                    }
                }, context.applicationContext)
                renderer = created
                created.setTargetFps(p.targetFps)
                manager?.let { created.setLevel(it.initialLevel) }
                created.setColorGrade(brightness, contrast, saturation)
                created.setColorTune(hue, temperature)
                created.setOrientation(orientation)
                created.setDeinterlace(deinterlace.shaderValue)
                if (aiFusion) created.setAiFusion(true)
                player?.let { pl ->
                    pl.addListener(playerListener)
                    val size = pl.videoSize
                    if (size.width > 0 && size.height > 0) created.setSourceSize(size.width, size.height)
                }
                created.start()
            }
        }
        applyFrameRateHint()
        applyDisplayMode()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = applyFrameRateHint()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val pl = player
        pl?.removeListener(playerListener)
        inputSurface?.let { pl?.clearVideoSurface(it) }
        inputSurface = null
        renderer?.release()
        renderer = null
        rifeRenderer?.release()
        rifeRenderer = null
        hybridRenderer?.release()
        hybridRenderer = null
        manager?.release()
        manager = null
        clearDisplayMode()
    }

    private fun findWindow(): Window? {
        var c: Context? = context
        while (c is ContextWrapper) { if (c is Activity) return c.window; c = c.baseContext }
        return null
    }

    /**
     * The window keeps the refresh rate chosen in Settings > Refresh Rate: the player controls, sheets and the rest of the
     * app run at that rate. Only the video is paced to the target FPS (on the app's vsyncs); no display mode is requested.
     */
    private fun applyDisplayMode() {
        val window = findWindow() ?: return
        try {
            com.beatraxus.app.perf.RefreshRateManager.videoModeOverride = false
            com.beatraxus.app.perf.RefreshRateManager.applyTo(window, context)
        } catch (t: Throwable) { Log.w(TAG, "keeping the app refresh rate failed", t) }
    }

    private fun clearDisplayMode() {
        val window = findWindow() ?: return
        try {
            com.beatraxus.app.perf.RefreshRateManager.videoModeOverride = false
            com.beatraxus.app.perf.RefreshRateManager.applyTo(window, context)
        } catch (_: Throwable) {}
    }

    /** Intentionally empty: a frame-rate vote on the video surface could pull the whole display away from the app's rate. */
    private fun applyFrameRateHint() {}

    private companion object { const val TAG = "FpsBoosterView" }
}
