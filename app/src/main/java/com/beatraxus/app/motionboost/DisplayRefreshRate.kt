package com.beatraxus.app.motionboost

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.abs

/** Reads the refresh rates the device panel supports, to tell which FPS Booster targets make sense. */
object DisplayRefreshRate {

    fun supportedRates(context: Context): List<Float> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rates = display?.supportedModes?.map { it.refreshRate }?.distinct().orEmpty()
        return rates.ifEmpty { listOf(60f) }
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
     * Picks the best display mode for [targetFps], keeping the current resolution so the screen does not
     * flicker through a resolution change. Without this, many phones stay in a 60 Hz mode (battery saver,
     * "standard" refresh setting, adaptive heuristics) and every FPS target ends up showing 60.
     */
    fun preferredMode(context: Context, targetFps: Int): ModeChoice? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val hz = MotionBoostTiming.pickRefreshRate(sameSize.map { it.refreshRate }, targetFps) ?: return null
        val mode = sameSize.firstOrNull { abs(it.refreshRate - hz) < 0.01f } ?: return null
        return ModeChoice(mode.modeId, mode.refreshRate)
    }

    fun supports(context: Context, targetFps: Int): Boolean =
        MotionBoostTiming.isRateSupported(supportedRates(context), targetFps)
}
