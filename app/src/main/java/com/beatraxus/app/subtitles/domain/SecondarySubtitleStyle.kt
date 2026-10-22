package com.beatraxus.app.subtitles.domain

import android.content.SharedPreferences

/** Where the second subtitle of a dual-subtitle pair is drawn (video feature 22). */
enum class SecondarySubtitlePlacement(val displayName: String) {
    ABOVE_PRIMARY("Above main"),
    TOP("Top of screen")
}

/** Look of the second subtitle. Size is a percentage of the main subtitle's size. */
data class SecondarySubtitleStyle(
    val placement: SecondarySubtitlePlacement = SecondarySubtitlePlacement.ABOVE_PRIMARY,
    val textColor: Int = DEFAULT_COLOR,
    val sizePercent: Int = DEFAULT_SIZE_PERCENT
) {
    companion object {
        /** Soft yellow, so the two languages are easy to tell apart at a glance. */
        // A plain ARGB literal, not Color.rgb(), so it also works in JVM unit tests where android.graphics is stubbed.
        const val DEFAULT_COLOR: Int = 0xFFFFE082.toInt()
        const val DEFAULT_SIZE_PERCENT = 85
        const val MIN_SIZE_PERCENT = 50
        const val MAX_SIZE_PERCENT = 150
    }
}

object SecondarySubtitlePrefs {
    const val KEY_ENABLED = "video_subtitle_dual_enabled"
    private const val KEY_PLACEMENT = "video_subtitle_dual_placement"
    private const val KEY_COLOR = "video_subtitle_dual_color"
    private const val KEY_SIZE_PERCENT = "video_subtitle_dual_size_percent"

    fun isEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_ENABLED, false)

    fun setEnabled(p: SharedPreferences, enabled: Boolean) {
        p.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun readStyle(p: SharedPreferences): SecondarySubtitleStyle = SecondarySubtitleStyle(
        placement = SecondarySubtitlePlacement.entries.firstOrNull {
            it.name == p.getString(KEY_PLACEMENT, SecondarySubtitlePlacement.ABOVE_PRIMARY.name)
        } ?: SecondarySubtitlePlacement.ABOVE_PRIMARY,
        textColor = p.getInt(KEY_COLOR, SecondarySubtitleStyle.DEFAULT_COLOR),
        sizePercent = p.getInt(KEY_SIZE_PERCENT, SecondarySubtitleStyle.DEFAULT_SIZE_PERCENT)
            .coerceIn(SecondarySubtitleStyle.MIN_SIZE_PERCENT, SecondarySubtitleStyle.MAX_SIZE_PERCENT)
    )

    fun writeStyle(p: SharedPreferences, s: SecondarySubtitleStyle) {
        p.edit()
            .putString(KEY_PLACEMENT, s.placement.name)
            .putInt(KEY_COLOR, s.textColor)
            .putInt(KEY_SIZE_PERCENT, s.sizePercent)
            .apply()
    }
}
