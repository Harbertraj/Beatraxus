package com.beatraxus.app.motionboost

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.Window
import com.beatraxus.app.perf.RefreshRateManager

/**
 * Keeps the panel on a refresh rate that shows the booster's target FPS evenly, for both video surfaces
 * ([com.beatraxus.app.fpsbooster.FpsBoosterSurfaceView] and [MotionBoostSurfaceView]). Main thread only.
 *
 * Three jobs, in this order of authority:
 *  1. **Mode.** When the app's own refresh rate is not a whole multiple of the target (45 / 90 FPS on a 120 Hz window,
 *     120 FPS while the app is set to 60 Hz) the window asks for the better-fitting mode of the same resolution
 *     (`preferredDisplayModeId`). Otherwise the app's rate is kept, so the player UI never runs slower than the
 *     user chose in Settings > Refresh Rate.
 *  2. **Vote.** The video surface tells the system which rate it wants ([Surface.setFrameRate], API 30+, seamless
 *     switches only from API 31 so there is no black flash). The vote is the panel rate chosen in step 1, never the
 *     target itself, so it can only agree with the window and can never pull the display below the app's rate.
 *     It stops OEM "video means low refresh" policies from dropping the panel under the booster.
 *  3. **Watch.** If the system moves the panel to a rate that does not show the target evenly (battery saver, thermal,
 *     OEM policy), the request is repeated, at most [MAX_REASSERTS] times per session. Whatever the outcome, the
 *     presenter's [com.beatraxus.app.fpsbooster.CadenceGovernor] keeps the output on an exact n-vsync grid.
 */
class BoosterDisplaySession(
    private val context: Context,
    private val windowProvider: () -> Window?,
    private val surfaceProvider: () -> Surface?
) {
    private val main = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private var targetFps = 0
    private var started = false
    private var reasserts = 0

    /** Panel rate (Hz) this session asked for; 0 until [apply] ran. */
    var requestedHz: Float = 0f
        private set

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !started) return
            val actual = DisplayRefreshRate.currentRate(context)
            if (MotionBoostTiming.isCleanMultiple(actual, targetFps)) return
            if (reasserts >= MAX_REASSERTS) return
            reasserts++
            Log.i(TAG, "display now at %.1f Hz, which does not show $targetFps FPS evenly: asking again (%d/%d)"
                .format(actual, reasserts, MAX_REASSERTS))
            apply(force = true)
        }
    }

    /** Starts watching the display and applies the mode / vote for [targetFps]. */
    fun start(targetFps: Int) {
        this.targetFps = targetFps
        if (!started) {
            started = true
            reasserts = 0
            runCatching { displayManager?.registerDisplayListener(listener, main) }
        }
        apply()
    }

    /** A new target (same session). */
    fun update(targetFps: Int) {
        if (targetFps == this.targetFps && started) return
        this.targetFps = targetFps
        reasserts = 0
        if (started) apply()
    }

    /** Re-applies after the surface was (re)created: the frame-rate vote belongs to the surface, not to the window. */
    fun onSurfaceChanged() { if (started) applyVote(requestedHz) }

    fun stop() {
        if (started) runCatching { displayManager?.unregisterDisplayListener(listener) }
        started = false
        requestedHz = 0f
        val window = windowProvider() ?: return
        try {
            RefreshRateManager.videoModeOverride = false
            // Back to the rate chosen for the app (not to "no preference", which can fall back to 60 Hz).
            RefreshRateManager.applyTo(window, context)
        } catch (_: Throwable) {
        }
    }

    private fun apply(force: Boolean = false) {
        val window = windowProvider() ?: return
        val target = targetFps
        if (target <= 0) return
        try {
            // Request a better-fitting panel mode only when the app's rate does not suit the target.
            val choice = DisplayRefreshRate.modeSwitchFor(context, target)
            if (choice != null) {
                RefreshRateManager.videoModeOverride = true
                val lp = window.attributes
                if (force || lp.preferredDisplayModeId != choice.modeId || lp.preferredRefreshRate != choice.refreshRate) {
                    lp.preferredDisplayModeId = choice.modeId
                    lp.preferredRefreshRate = choice.refreshRate
                    window.attributes = lp
                }
                requestedHz = choice.refreshRate
            } else {
                RefreshRateManager.videoModeOverride = false
                RefreshRateManager.applyTo(window, context)
                requestedHz = RefreshRateManager.appRate(context) ?: DisplayRefreshRate.currentRate(context)
            }
            applyVote(requestedHz)
        } catch (t: Throwable) {
            Log.w(TAG, "keeping the display rate for the booster failed", t)
        }
    }

    /** Surface-level frame-rate vote: the rate the panel should run at, switched only when the switch is seamless. */
    private fun applyVote(hz: Float) {
        if (hz <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = surfaceProvider()?.takeIf { it.isValid } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame-rate vote refused", t)
        }
    }

    companion object {
        private const val TAG = "BoosterDisplay"
        const val MAX_REASSERTS = 3
    }
}
