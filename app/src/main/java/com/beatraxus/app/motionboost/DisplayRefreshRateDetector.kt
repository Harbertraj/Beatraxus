package com.beatraxus.app.motionboost

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager

/**
 * Utility to detect the current and supported refresh rates of the device display.
 */
object DisplayRefreshRateDetector {
    fun getRefreshRate(context: Context): Float {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        
        val modes = display?.supportedModes
        val rates = modes?.map { it.refreshRate } ?: listOf(60f)
        Log.d("RefreshRateDetector", "Supported refresh rates: $rates")
        
        val peak = rates.maxOrNull() ?: 60f
        Log.d("RefreshRateDetector", "Peak refresh rate selected: $peak Hz")
        return peak
    }

    fun getSupportedRefreshRates(context: Context): List<Float> {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return display?.supportedModes?.map { it.refreshRate }?.distinct() ?: listOf(60f)
    }
}
