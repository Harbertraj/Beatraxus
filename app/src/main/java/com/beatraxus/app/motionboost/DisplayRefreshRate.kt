package com.beatraxus.app.motionboost

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Reads the refresh rates the device panel supports, to tell which FPS Booster targets make sense. */
object DisplayRefreshRate {

    fun supportedRates(context: Context): List<Float> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rates = display?.supportedModes?.map { it.refreshRate }?.distinct().orEmpty()
        return rates.ifEmpty { listOf(60f) }
    }

    fun peakRate(context: Context): Float = supportedRates(context).max()

    fun supports(context: Context, targetFps: Int): Boolean =
        MotionBoostTiming.isRateSupported(supportedRates(context), targetFps)
}
