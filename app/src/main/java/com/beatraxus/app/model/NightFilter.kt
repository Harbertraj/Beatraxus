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
 * A blue-light cut over the whole player (picture, controls and sheets), so late-night viewing is easier on the
 * eyes. It is a multiply layer drawn over the player rather than inside the video pipeline, so it never touches the
 * video itself or screenshots taken straight from the video surface. The picture is drawn on a TextureView while
 * the filter is on, because a plain SurfaceView cannot be multiplied; the FPS Booster falls back to a flat veil.
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

    /** Overlay colour as 0xAARRGGBB, for the old flat-tint fallback (see [NightFilterMath.overlayArgb]). */
    val overlayArgb: Int get() = NightFilterMath.overlayArgb(strength)

    /**
     * Opaque 0xFFRRGGBB colour to MULTIPLY over the picture: red stays, green dips a little and blue drops a lot.
     * Real blue-light cut, black stays black and white turns warm instead of getting a yellow haze.
     */
    val multiplyArgb: Int get() = NightFilterMath.multiplyArgb(strength)

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

    /** How much blue / green is removed at 100 % strength (0.75 = blue drops to 25 %). Red is never touched. */
    const val MAX_BLUE_CUT = 0.75f
    const val MAX_GREEN_CUT = 0.30f

    fun greenGain(strength: Int): Float = 1f - MAX_GREEN_CUT * clampStrength(strength) / 100f

    fun blueGain(strength: Int): Float = 1f - MAX_BLUE_CUT * clampStrength(strength) / 100f

    /**
     * Colour to multiply over the picture (0xFFRRGGBB). Multiplying scales each channel, so blue is really reduced,
     * blacks stay black and contrast is kept. Strength 0 is pure white = no change.
     */
    fun multiplyArgb(strength: Int): Int {
        val r = 255
        val g = (255f * greenGain(strength)).roundToInt()
        val b = (255f * blueGain(strength)).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Old flat amber veil. Only used as a fallback where the picture cannot be multiplied (the FPS Booster draws the
     * video on its own GL surface). It adds a yellow haze, so the multiply colour above is preferred.
     */
    fun overlayArgb(strength: Int): Int {
        val s = clampStrength(strength) / 100f
        val a = (alpha(strength) * 255f).roundToInt()
        val r = 255
        val g = (150f - 60f * s).roundToInt() // 150 -> 90
        val b = 0
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
