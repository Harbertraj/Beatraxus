package com.beatraxus.app.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** When the night filter is applied. */
enum class NightFilterMode(val label: String, val description: String) {
    OFF("Off", "No tint"),
    ON("On", "Always warm the picture"),
    AUTO("Auto", "Only in the evening and at night (8 pm to 7 am)");

    companion object {
        fun fromName(name: String?): NightFilterMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Video feature 17: night / blue-light filter.
 *
 * A warm amber veil over the whole player (picture, controls and sheets), so late-night viewing is easier on the
 * eyes. It is drawn as a translucent overlay rather than inside the video pipeline, so it works the same for every
 * way the picture is drawn (normal, rotated, FPS Booster) and never touches the video itself or screenshots taken
 * straight from the video surface.
 *
 * Pure maths, no Android types, so it can be unit tested.
 */
data class NightFilterSettings(
    val mode: NightFilterMode = NightFilterMode.OFF,
    /** 0..100: how warm / strong the tint is. */
    val strength: Int = DEFAULT_STRENGTH,
    val autoStartHour: Int = AUTO_START_HOUR,
    val autoEndHour: Int = AUTO_END_HOUR
) {
    /** [hourOfDay] is the local hour, 0..23. */
    fun isActive(hourOfDay: Int): Boolean = when (mode) {
        NightFilterMode.OFF -> false
        NightFilterMode.ON -> true
        NightFilterMode.AUTO -> NightFilterMath.inWindow(hourOfDay, autoStartHour, autoEndHour)
    }

    /** Overlay colour as 0xAARRGGBB. */
    val overlayArgb: Int get() = NightFilterMath.overlayArgb(strength)

    companion object {
        const val DEFAULT_STRENGTH = 50
        const val AUTO_START_HOUR = 20
        const val AUTO_END_HOUR = 7

        const val KEY_MODE = "video_night_filter_mode"
        const val KEY_STRENGTH = "video_night_filter_strength"
    }
}

object NightFilterMath {
    /** Weakest and strongest overlay opacity. Even at 100 % the picture stays clearly visible. */
    const val MIN_ALPHA = 0.05f
    const val MAX_ALPHA = 0.55f

    fun clampStrength(strength: Int): Int = min(100, max(0, strength))

    /** True when [hour] is in the window that starts at [start] and ends (exclusive) at [end]; the window may pass midnight. */
    fun inWindow(hour: Int, start: Int, end: Int): Boolean {
        val h = ((hour % 24) + 24) % 24
        val s = ((start % 24) + 24) % 24
        val e = ((end % 24) + 24) % 24
        return when {
            s == e -> false // empty window
            s < e -> h in s until e
            else -> h >= s || h < e // e.g. 20 -> 7
        }
    }

    fun alpha(strength: Int): Float = MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * clampStrength(strength) / 100f

    /** Amber that turns redder as the strength grows (less blue and green reach the eye). */
    fun overlayArgb(strength: Int): Int {
        val s = clampStrength(strength) / 100f
        val a = (alpha(strength) * 255f).roundToInt()
        val r = 255
        val g = (150f - 60f * s).roundToInt() // 150 -> 90
        val b = 0
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
