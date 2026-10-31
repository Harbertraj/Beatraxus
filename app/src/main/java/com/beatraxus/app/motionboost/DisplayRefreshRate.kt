package com.beatraxus.app.motionboost

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.beatraxus.app.perf.RefreshRateManager
import kotlin.math.abs

/** Reads the refresh rates the device panel supports, to tell which FPS Booster targets make sense. */
object DisplayRefreshRate {

    fun supportedRates(context: Context): List<Float> {
        // Every refresh rate the panel really offers at its current resolution. Which FPS targets are selectable is
        // decided by the panel's highest rate, not by the rate the app window is currently pinned to.
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val current = display?.mode
        val rates = display?.supportedModes
            ?.filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
            ?.map { it.refreshRate }?.distinct()?.sorted().orEmpty()
        return rates.ifEmpty { listOf(60f) }
    }

    /**
     * The display mode to request while the booster plays at [targetFps], or null to keep the app's own rate. A mode
     * is requested only when it differs from the app's rate (e.g. 90 Hz for 45 / 90 FPS on a 120 Hz window, 120 Hz
     * for 120 FPS when the app is set to 60 Hz).
     */
    fun modeSwitchFor(context: Context, targetFps: Int): ModeChoice? {
        val choice = preferredMode(context, targetFps) ?: return null
        val appHz = RefreshRateManager.appRate(context) ?: return choice
        return if (abs(choice.refreshRate - appHz) > 0.5f) choice else null
    }

    fun peakRate(context: Context): Float = supportedRates(context).max()

    /** The refresh rate the panel is running right now (what the user actually sees). */
    fun currentRate(context: Context): Float {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
    }

    /** A display mode (id + Hz) to request so the panel really runs at / above [targetFps]. */
    data class ModeChoice(val modeId: Int, val refreshRate: Float)

    /**
     * Picks the display mode to pin the app window to while a booster plays at [targetFps], keeping the current
     * resolution so the screen does not flicker through a resolution change. Without this, many phones stay in a
     * 60 Hz mode (battery saver, "standard" refresh setting, adaptive heuristics) and every FPS target ends up
     * showing 60.
     *
     * The mode is an integer multiple of the target (so the output is evenly paced) but is NOT the lowest one: the
     * window also draws the player controls and sheets, which must keep the app's normal refresh rate. See
     * [MotionBoostTiming.pickWindowRefreshRate].
     */
    fun preferredMode(context: Context, targetFps: Int): ModeChoice? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val hz = MotionBoostTiming.pickWindowRefreshRate(
            sameSize.map { it.refreshRate }, targetFps, RefreshRateManager.appRate(context)
        ) ?: return null
        val mode = sameSize.firstOrNull { abs(it.refreshRate - hz) < 0.01f } ?: return null
        return ModeChoice(mode.modeId, mode.refreshRate)
    }

    fun supports(context: Context, targetFps: Int): Boolean =
        MotionBoostTiming.isRateSupported(supportedRates(context), targetFps)
}
