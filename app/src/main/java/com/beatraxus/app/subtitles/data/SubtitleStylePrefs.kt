package com.beatraxus.app.subtitles.data

import android.content.SharedPreferences
import android.graphics.Color
import com.beatraxus.app.subtitles.domain.SubtitlePositionPreset

/**
 * Subtitle appearance as stored by the video player.
 *
 * The keys are the same ones `VideoPlayerViewModel` reads when a video opens and the in-player subtitle sheet writes,
 * so Settings -> Video Settings -> Subtitles and the player always agree.
 */
data class SubtitleStyle(
    val sizePercent: Int = 100,
    val textColor: Int = Color.WHITE,
    val outlineColor: Int = Color.BLACK,
    val edgeType: Int = EDGE_OUTLINE,
    val bold: Boolean = false,
    val backgroundOpacity: Float = 0f,
    val positionPreset: SubtitlePositionPreset = SubtitlePositionPreset.BOTTOM
) {
    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2
    }
}

object SubtitleStylePrefs {
    private const val KEY_SIZE = "video_subtitle_size"
    private const val KEY_SIZE_PERCENT = "video_subtitle_size_percent"
    private const val KEY_TEXT_COLOR = "video_subtitle_text_color"
    private const val KEY_BG_COLOR = "video_subtitle_bg_color"
    private const val KEY_OUTLINE_COLOR = "video_subtitle_outline_color"
    private const val KEY_WINDOW_COLOR = "video_subtitle_window_color"
    private const val KEY_ALPHA = "video_subtitle_alpha"
    private const val KEY_OFFSET = "video_subtitle_offset"
    private const val KEY_BOLD = "video_subtitle_bold"
    private const val KEY_EDGE_TYPE = "video_subtitle_edge_type"
    private const val KEY_BG_OPACITY = "video_subtitle_bg_opacity"
    private const val KEY_POS_PRESET = "video_subtitle_pos_preset"

    const val BASE_SIZE_SP = 18f
    const val MIN_SIZE_SP = 12f

    /** Text size in sp for a size percentage (same rule as `VideoPlayerViewModel.setSubtitleSizePercent`). */
    fun sizeSpFor(percent: Int): Float = (BASE_SIZE_SP * (percent / 100f)).coerceAtLeast(MIN_SIZE_SP)

    fun read(p: SharedPreferences): SubtitleStyle = SubtitleStyle(
        sizePercent = p.getInt(KEY_SIZE_PERCENT, 100).coerceIn(50, 200),
        textColor = p.getInt(KEY_TEXT_COLOR, Color.WHITE),
        outlineColor = p.getInt(KEY_OUTLINE_COLOR, Color.BLACK),
        edgeType = p.getInt(KEY_EDGE_TYPE, SubtitleStyle.EDGE_OUTLINE),
        bold = p.getBoolean(KEY_BOLD, false),
        backgroundOpacity = p.getFloat(KEY_BG_OPACITY, 0f).coerceIn(0f, 1f),
        positionPreset = SubtitlePositionPreset.entries.firstOrNull {
            it.name == p.getString(KEY_POS_PRESET, SubtitlePositionPreset.BOTTOM.name)
        } ?: SubtitlePositionPreset.BOTTOM
    )

    fun write(p: SharedPreferences, s: SubtitleStyle) {
        p.edit()
            .putInt(KEY_SIZE_PERCENT, s.sizePercent)
            .putFloat(KEY_SIZE, sizeSpFor(s.sizePercent))
            .putInt(KEY_TEXT_COLOR, s.textColor)
            .putInt(KEY_OUTLINE_COLOR, s.outlineColor)
            .putInt(KEY_EDGE_TYPE, s.edgeType)
            .putBoolean(KEY_BOLD, s.bold)
            .putFloat(KEY_BG_OPACITY, s.backgroundOpacity)
            .putString(KEY_POS_PRESET, s.positionPreset.name)
            .apply()
    }

    /** Same defaults as `VideoPlayerViewModel.resetSubtitleStyle`. */
    fun reset(p: SharedPreferences) {
        p.edit()
            .putFloat(KEY_SIZE, BASE_SIZE_SP)
            .putInt(KEY_TEXT_COLOR, Color.WHITE)
            .putInt(KEY_BG_COLOR, Color.TRANSPARENT)
            .putInt(KEY_OUTLINE_COLOR, Color.BLACK)
            .putInt(KEY_WINDOW_COLOR, Color.TRANSPARENT)
            .putFloat(KEY_ALPHA, 1f)
            .putFloat(KEY_OFFSET, 0f)
            .putBoolean(KEY_BOLD, false)
            .putInt(KEY_EDGE_TYPE, SubtitleStyle.EDGE_OUTLINE)
            .putFloat(KEY_BG_OPACITY, 0f)
            .putString(KEY_POS_PRESET, SubtitlePositionPreset.BOTTOM.name)
            .putInt(KEY_SIZE_PERCENT, 100)
            .apply()
    }
}
