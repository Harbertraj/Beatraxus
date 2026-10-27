package com.beatraxus.app.perf

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * App-wide screen refresh rate (Settings > Appearance > Refresh Rate).
 *
 * [AUTO] (the default) finds the highest refresh rate the panel offers at its current resolution and pins the app
 * window to it, so a 120 Hz phone does not drop to 60 Hz just because the app never asked for more. A fixed value
 * (60 / 90 / 120 / 144) pins the window to the closest mode the panel really has.
 *
 * The resolution is never changed (no flicker); only modes of the current size are considered.
 */
object RefreshRateManager {
    const val AUTO = 0
    val FIXED_OPTIONS = listOf(60, 90, 120, 144)

    private const val TAG = "RefreshRateManager"
    private const val PREFS = "beatraxus_display"
    private const val KEY_RATE = "app_refresh_rate"

    private val _selected = MutableStateFlow(AUTO)

    /** [AUTO] or the chosen rate in Hz. */
    val selected: StateFlow<Int> = _selected.asStateFlow()

    /**
     * True while a video booster has asked for its own display mode (a multiple of the video's frame rate). The app's
     * rate must not override that; it is set back as soon as the booster lets go.
     */
    @Volatile
    var videoModeOverride: Boolean = false

    /** Reads the saved choice. Safe to call from any thread. */
    fun init(context: Context) {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RATE, AUTO)
        _selected.value = if (saved == AUTO || saved in FIXED_OPTIONS) saved else AUTO
    }

    fun set(context: Context, hz: Int) {
        val value = if (hz in FIXED_OPTIONS) hz else AUTO
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_RATE, value).apply()
        _selected.value = value
    }

    private fun defaultDisplay(context: Context): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** Modes of the panel's current resolution. */
    private fun modesAtCurrentSize(display: Display): List<Display.Mode> {
        val current = display.mode
        return display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
    }

    /** Refresh rates (whole Hz, ascending) the panel offers at its current resolution. */
    fun supportedRates(context: Context): List<Int> {
        val display = defaultDisplay(context) ?: return listOf(60)
        return modesAtCurrentSize(display).map { it.refreshRate.roundToInt() }.distinct().sorted().ifEmpty { listOf(60) }
    }

    /** Highest refresh rate of the panel (what [AUTO] pins the app to). */
    fun peakRate(context: Context): Int = supportedRates(context).max()

    private fun pickMode(modes: List<Display.Mode>, selected: Int): Display.Mode? {
        if (modes.isEmpty()) return null
        return if (selected == AUTO) {
            modes.maxByOrNull { it.refreshRate }
        } else {
            // Closest rate wins; on a tie the higher one (smoother).
            modes.minWithOrNull(compareBy<Display.Mode>({ abs(it.refreshRate - selected) }, { -it.refreshRate }))
        }
    }

    /** Pins [window] to the mode that matches the saved choice. Does nothing when the window already asks for it. */
    fun applyTo(window: Window, context: Context) {
        if (videoModeOverride) return
        try {
            val display = defaultDisplay(context) ?: return
            val mode = pickMode(modesAtCurrentSize(display), _selected.value) ?: return
            val lp = window.attributes
            if (lp.preferredDisplayModeId != mode.modeId || lp.preferredRefreshRate != mode.refreshRate) {
                lp.preferredDisplayModeId = mode.modeId
                lp.preferredRefreshRate = mode.refreshRate
                window.attributes = lp
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not apply the refresh rate", t)
        }
    }
}
