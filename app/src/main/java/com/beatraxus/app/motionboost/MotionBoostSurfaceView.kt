package com.beatraxus.app.motionboost

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
import com.beatraxus.app.model.VideoOrientation
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * Video output used while the FPS Booster is on. Instead of letting ExoPlayer draw straight to the
 * screen, ExoPlayer is pointed at the [MotionBoostRenderer]'s input surface and the renderer presents
 * the (interpolated) frames here.
 *
 * Add it to PlayerView's content frame *below* the subtitle layer (index 0), the same place PlayerView
 * puts its own surface, and build the PlayerView with `surface_type="none"`.
 */
class MotionBoostSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var player: Player? = null

    /** Called on the main thread if the GL pipeline cannot run; the caller should fall back to Original. */
    var onError: ((String) -> Unit)? = null

    /** Called on the main thread about once per second. */
    var onStats: ((sourceFps: Float, outputFps: Float) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var renderer: MotionBoostRenderer? = null
    private var inputSurface: Surface? = null
    private var targetFps = 60
    private var extreme = false
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var orientation = VideoOrientation.NONE

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            renderer?.setSourceSize(videoSize.width, videoSize.height)
        }
    }

    init {
        holder.addCallback(this)
    }

    fun setTargetFps(fps: Int) {
        val changed = fps != targetFps
        targetFps = fps
        renderer?.setTargetFps(fps)
        applyFrameRateHint()
        if (changed) applyDisplayMode()
    }

    /** Extreme performance: lighter working resolution + performance hints for the highest FPS. */
    fun setExtreme(enabled: Boolean) {
        extreme = enabled
        renderer?.setExtreme(enabled)
    }

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        renderer?.setColorGrade(brightness, contrast, saturation)
    }

    /** Rotate / flip / mirror of the picture; drawn by the renderer, since a SurfaceView cannot be transformed. */
    fun setOrientation(orientation: VideoOrientation) {
        this.orientation = orientation
        renderer?.setOrientation(orientation)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lateinit var created: MotionBoostRenderer
        created = MotionBoostRenderer(holder.surface, object : MotionBoostRenderer.Callbacks {
            override fun onInputSurfaceReady(surface: Surface) {
                main.post {
                    if (renderer === created) {
                        inputSurface = surface
                        player?.setVideoSurface(surface)
                    }
                }
            }

            override fun onStats(sourceFps: Float, outputFps: Float) {
                main.post { if (renderer === created) onStats?.invoke(sourceFps, outputFps) }
            }

            override fun onError(message: String) {
                Log.e(TAG, "FPS Booster failed: $message")
                main.post { if (renderer === created) onError?.invoke(message) }
            }
        }, context.applicationContext)
        renderer = created
        created.setTargetFps(targetFps)
        created.setExtreme(extreme)
        created.setColorGrade(brightness, contrast, saturation)
        created.setOrientation(orientation)
        player?.let { p ->
            p.addListener(playerListener)
            val size = p.videoSize
            if (size.width > 0 && size.height > 0) created.setSourceSize(size.width, size.height)
        }
        created.start()
        applyFrameRateHint()
        applyDisplayMode()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFrameRateHint()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Detach ExoPlayer first so the codec stops writing into the input surface.
        val p = player
        p?.removeListener(playerListener)
        inputSurface?.let { p?.clearVideoSurface(it) }
        inputSurface = null
        renderer?.release()
        renderer = null
        clearDisplayMode()
    }

    private fun findWindow(): Window? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c.window
            c = c.baseContext
        }
        return null
    }

    /**
     * Makes the panel actually run at the chosen rate. setFrameRate() alone is only a hint, and on a lot of
     * phones the display stays in a 60 Hz mode, so 90 / 120 FPS ended up showing 60. Requesting the display
     * mode directly (same resolution, matching refresh rate) fixes that.
     */
    private fun applyDisplayMode() {
        val window = findWindow() ?: return
        if (targetFps <= 0) return
        try {
            val choice = DisplayRefreshRate.preferredMode(context, targetFps) ?: return
            val lp = window.attributes
            if (lp.preferredDisplayModeId != choice.modeId) {
                lp.preferredDisplayModeId = choice.modeId
                window.attributes = lp
            }
        } catch (t: Throwable) {
            Log.w(TAG, "preferred display mode failed", t)
        }
    }

    private fun clearDisplayMode() {
        val window = findWindow() ?: return
        try {
            val lp = window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                window.attributes = lp
            }
        } catch (_: Throwable) {
        }
    }

    /** Asks the system to run the panel at (a multiple of) the target rate so frames are paced evenly. */
    private fun applyFrameRateHint() {
        val surface = holder.surface
        if (targetFps <= 0 || surface == null || !surface.isValid) return
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    surface.setFrameRate(
                        targetFps.toFloat(),
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    surface.setFrameRate(targetFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setFrameRate failed", t)
        }
    }

    private companion object {
        const val TAG = "MotionBoostView"
    }
}
