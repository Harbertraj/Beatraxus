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
 * Video output used while the optical-flow FPS Booster is running. ExoPlayer is pointed at the renderer's input
 * surface and [OpticalFlowRenderer] presents the (interpolated) frames here. Same placement rules as the
 * classic MotionBoostSurfaceView: add it to PlayerView's content frame at index 0 (below the subtitle layer) and
 * build the PlayerView with `surface_type="none"`.
 *
 * Surface destruction / recreation (rotation, backgrounding, full-screen toggles) tears the renderer down and
 * builds a new one on the next surfaceCreated; ExoPlayer is detached first so the codec never writes into a
 * released surface.
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

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            renderer?.setSourceSize(videoSize.width, videoSize.height)
        }
    }

    init { holder.addCallback(this) }

    /** Idempotent: Compose calls this on every recomposition, so only a real change restarts the session. */
    fun configure(plan: BoosterPlan, settings: FpsBoosterSettings) {
        val key = plan to settings
        if (key == applied) return
        val needsNewSession = applied == null ||
            applied!!.first.quality != plan.quality ||
            applied!!.second.adaptiveResolution != settings.adaptiveResolution ||
            applied!!.second.batterySaver != settings.batterySaver
        val targetChanged = applied?.first?.targetFps != plan.targetFps
        this.plan = plan
        this.settings = settings
        applied = key
        renderer?.setTargetFps(plan.targetFps)
        applyFrameRateHint()
        applyDisplayMode()
        if (needsNewSession && renderer != null) {
            startManager()
            renderer?.setLevel(manager!!.initialLevel)
        } else if (targetChanged) {
            manager?.restartGrace() // output re-pacing is not an overload
        }
    }

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness; this.contrast = contrast; this.saturation = saturation
        renderer?.setColorGrade(brightness, contrast, saturation)
    }

    /** Hue (degrees) and colour temperature (-1..1). Compose calls this on every recomposition, so only a change is passed on. */
    fun setColorTune(hueDegrees: Float, temperature: Float) {
        if (hueDegrees == hue && temperature == this.temperature) return
        hue = hueDegrees
        this.temperature = temperature
        renderer?.setColorTune(hueDegrees, temperature)
    }

    /** Rotate / flip / mirror of the picture; drawn by the renderer, since a SurfaceView cannot be transformed. */
    fun setOrientation(orientation: VideoOrientation) {
        this.orientation = orientation
        renderer?.setOrientation(orientation)
    }

    /**
     * Deinterlacing. Done here, in the booster's own copy pass: media3's effect pipeline cannot draw into the bare
     * Surface the booster hands to ExoPlayer (black picture, sound only), so the player has no deinterlace effect
     * while the booster runs.
     */
    fun setDeinterlace(mode: DeinterlaceMode) {
        if (mode == deinterlace) return
        deinterlace = mode
        renderer?.setDeinterlace(mode.shaderValue)
    }

    private fun startManager() {
        manager?.release()
        val p = plan ?: return
        lateinit var created: FpsBoosterManager
        created = FpsBoosterManager(context, p, settings, DisplayRefreshRate.supportedRates(context), object : FpsBoosterManager.Listener {
            override fun onQualityLevel(level: QualityLevel) { if (manager === created) renderer?.setLevel(level) }
            override fun onStats(stats: BoosterStats) { if (manager === created) listener?.onStats(stats) }
            override fun onFallback(reason: FallbackReason) { if (manager === created) listener?.onFallback(reason, null) }
        })
        manager = created
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val p = plan ?: return
        if (manager == null) startManager()
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
        player?.let { pl ->
            pl.addListener(playerListener)
            val size = pl.videoSize
            if (size.width > 0 && size.height > 0) created.setSourceSize(size.width, size.height)
        }
        created.start()
        applyFrameRateHint()
        applyDisplayMode()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = applyFrameRateHint()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val pl = player
        pl?.removeListener(playerListener)
        inputSurface?.let { pl?.clearVideoSurface(it) }   // detach the codec before the renderer frees the surface
        inputSurface = null
        renderer?.release()
        renderer = null
        manager?.release()
        manager = null
        clearDisplayMode()
    }

    // Display-mode helpers mirror MotionBoostSurfaceView (kept separate so the classic engine stays untouched).
    // They only request a panel mode that is an exact multiple of the target; they never change the generated rate.

    private fun findWindow(): Window? {
        var c: Context? = context
        while (c is ContextWrapper) { if (c is Activity) return c.window; c = c.baseContext }
        return null
    }

    private fun applyDisplayMode() {
        val window = findWindow() ?: return
        val fps = plan?.targetFps ?: return
        try {
            val choice = DisplayRefreshRate.preferredMode(context, fps) ?: return
            com.beatraxus.app.perf.RefreshRateManager.videoModeOverride = true
            val lp = window.attributes
            if (lp.preferredDisplayModeId != choice.modeId) { lp.preferredDisplayModeId = choice.modeId; window.attributes = lp }
        } catch (t: Throwable) { Log.w(TAG, "preferred display mode failed", t) }
    }

    private fun clearDisplayMode() {
        val window = findWindow() ?: return
        try {
            com.beatraxus.app.perf.RefreshRateManager.videoModeOverride = false
            // Back to the refresh rate chosen for the app (not to "no preference", which can fall back to 60 Hz).
            com.beatraxus.app.perf.RefreshRateManager.applyTo(window, context)
        } catch (_: Throwable) {}
    }

    private fun applyFrameRateHint() {
        val fps = plan?.targetFps ?: return
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> surface.setFrameRate(
                    fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        } catch (t: Throwable) { Log.w(TAG, "setFrameRate failed", t) }
    }

    private companion object { const val TAG = "FpsBoosterView" }
}
