package com.beatraxus.app.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo

/** Orientation the video player opens in (MX Player / VLC "screen orientation"). */
enum class VideoOrientationMode(val label: String, val requestedOrientation: Int) {
    LANDSCAPE("Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    AUTO("Auto-rotate", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR),
    PORTRAIT("Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);

    companion object {
        fun fromName(name: String?): VideoOrientationMode = entries.firstOrNull { it.name == name } ?: LANDSCAPE
    }
}

/**
 * Video player preferences edited under Settings -> Video Settings.
 *
 * The player reads them once when it opens, so a change applies from the next video.
 * Subtitle *appearance* lives in [SubtitleStylePrefs] because the in-player subtitle sheet already owns those keys.
 */
data class VideoPlayerOptions(
    // Gestures
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureSeek: Boolean = true,
    val gestureZoom: Boolean = true,
    val gestureSubtitleMove: Boolean = true,
    val doubleTapSeekSeconds: Int = 10,
    val doubleTapPlayPause: Boolean = true,
    val holdToSpeedEnabled: Boolean = true,
    val holdSpeed: Float = 2f,
    // Playback
    val defaultSpeed: Float = 1f,
    val resumePlayback: Boolean = true,
    val autoPlayNext: Boolean = true,
    val pauseOnHeadsetUnplug: Boolean = true,
    val keepScreenOn: Boolean = true,
    val orientationMode: VideoOrientationMode = VideoOrientationMode.LANDSCAPE,
    // Languages (BCP-47 / ISO 639 code, null = let the player decide)
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null
) {
    /** Double-tap seek distance in milliseconds. */
    val doubleTapSeekMs: Long get() = doubleTapSeekSeconds * 1000L
}

object VideoPlayerPrefs {
    const val PREFS_NAME = "beatraxus"

    const val KEY_GESTURE_BRIGHTNESS = "video_gesture_brightness"
    const val KEY_GESTURE_VOLUME = "video_gesture_volume"
    const val KEY_GESTURE_SEEK = "video_gesture_seek"
    const val KEY_GESTURE_ZOOM = "video_gesture_zoom"
    const val KEY_GESTURE_SUBTITLE_MOVE = "video_gesture_subtitle_move"
    const val KEY_DOUBLE_TAP_SEEK_SECONDS = "video_double_tap_seek_seconds"
    const val KEY_DOUBLE_TAP_PLAY_PAUSE = "video_double_tap_play_pause"
    const val KEY_HOLD_TO_SPEED = "video_hold_to_speed"
    const val KEY_HOLD_SPEED = "video_hold_speed"
    const val KEY_DEFAULT_SPEED = "video_default_speed"
    const val KEY_RESUME_PLAYBACK = "video_resume_playback"
    const val KEY_AUTO_PLAY_NEXT = "video_auto_play_next"
    const val KEY_PAUSE_ON_HEADSET_UNPLUG = "video_pause_on_headset_unplug"
    const val KEY_KEEP_SCREEN_ON = "video_keep_screen_on"
    const val KEY_ORIENTATION_MODE = "video_orientation_mode"
    const val KEY_PREFERRED_AUDIO_LANGUAGE = "video_preferred_audio_language"
    const val KEY_PREFERRED_SUBTITLE_LANGUAGE = "video_preferred_subtitle_language"

    val DOUBLE_TAP_SECONDS_CHOICES = listOf(5, 10, 15, 20, 30)
    val HOLD_SPEED_CHOICES = listOf(1.5f, 2f, 3f, 4f)
    val DEFAULT_SPEED_CHOICES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** First entry (null) means "Auto": the player picks the track like it always did. */
    val LANGUAGE_CHOICES: List<Pair<String?, String>> = listOf(
        null to "Auto",
        "en" to "English",
        "hi" to "Hindi",
        "ta" to "Tamil",
        "te" to "Telugu",
        "ml" to "Malayalam",
        "kn" to "Kannada",
        "bn" to "Bengali",
        "mr" to "Marathi",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "ar" to "Arabic",
        "tr" to "Turkish",
        "it" to "Italian"
    )

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): VideoPlayerOptions = read(prefs(context))

    fun read(p: SharedPreferences): VideoPlayerOptions = VideoPlayerOptions(
        gestureBrightness = p.getBoolean(KEY_GESTURE_BRIGHTNESS, true),
        gestureVolume = p.getBoolean(KEY_GESTURE_VOLUME, true),
        gestureSeek = p.getBoolean(KEY_GESTURE_SEEK, true),
        gestureZoom = p.getBoolean(KEY_GESTURE_ZOOM, true),
        gestureSubtitleMove = p.getBoolean(KEY_GESTURE_SUBTITLE_MOVE, true),
        doubleTapSeekSeconds = coerceDoubleTapSeconds(p.getInt(KEY_DOUBLE_TAP_SEEK_SECONDS, 10)),
        doubleTapPlayPause = p.getBoolean(KEY_DOUBLE_TAP_PLAY_PAUSE, true),
        holdToSpeedEnabled = p.getBoolean(KEY_HOLD_TO_SPEED, true),
        holdSpeed = coerceHoldSpeed(p.getFloat(KEY_HOLD_SPEED, 2f)),
        defaultSpeed = coerceDefaultSpeed(p.getFloat(KEY_DEFAULT_SPEED, 1f)),
        resumePlayback = p.getBoolean(KEY_RESUME_PLAYBACK, true),
        autoPlayNext = p.getBoolean(KEY_AUTO_PLAY_NEXT, true),
        pauseOnHeadsetUnplug = p.getBoolean(KEY_PAUSE_ON_HEADSET_UNPLUG, true),
        keepScreenOn = p.getBoolean(KEY_KEEP_SCREEN_ON, true),
        orientationMode = VideoOrientationMode.fromName(p.getString(KEY_ORIENTATION_MODE, null)),
        preferredAudioLanguage = p.getString(KEY_PREFERRED_AUDIO_LANGUAGE, null)?.takeIf { it.isNotBlank() },
        preferredSubtitleLanguage = p.getString(KEY_PREFERRED_SUBTITLE_LANGUAGE, null)?.takeIf { it.isNotBlank() }
    )

    fun write(p: SharedPreferences, o: VideoPlayerOptions) {
        p.edit()
            .putBoolean(KEY_GESTURE_BRIGHTNESS, o.gestureBrightness)
            .putBoolean(KEY_GESTURE_VOLUME, o.gestureVolume)
            .putBoolean(KEY_GESTURE_SEEK, o.gestureSeek)
            .putBoolean(KEY_GESTURE_ZOOM, o.gestureZoom)
            .putBoolean(KEY_GESTURE_SUBTITLE_MOVE, o.gestureSubtitleMove)
            .putInt(KEY_DOUBLE_TAP_SEEK_SECONDS, coerceDoubleTapSeconds(o.doubleTapSeekSeconds))
            .putBoolean(KEY_DOUBLE_TAP_PLAY_PAUSE, o.doubleTapPlayPause)
            .putBoolean(KEY_HOLD_TO_SPEED, o.holdToSpeedEnabled)
            .putFloat(KEY_HOLD_SPEED, coerceHoldSpeed(o.holdSpeed))
            .putFloat(KEY_DEFAULT_SPEED, coerceDefaultSpeed(o.defaultSpeed))
            .putBoolean(KEY_RESUME_PLAYBACK, o.resumePlayback)
            .putBoolean(KEY_AUTO_PLAY_NEXT, o.autoPlayNext)
            .putBoolean(KEY_PAUSE_ON_HEADSET_UNPLUG, o.pauseOnHeadsetUnplug)
            .putBoolean(KEY_KEEP_SCREEN_ON, o.keepScreenOn)
            .putString(KEY_ORIENTATION_MODE, o.orientationMode.name)
            .putString(KEY_PREFERRED_AUDIO_LANGUAGE, o.preferredAudioLanguage ?: "")
            .putString(KEY_PREFERRED_SUBTITLE_LANGUAGE, o.preferredSubtitleLanguage ?: "")
            .apply()
    }

    fun coerceDoubleTapSeconds(seconds: Int): Int =
        if (seconds in DOUBLE_TAP_SECONDS_CHOICES) seconds else 10

    fun coerceHoldSpeed(speed: Float): Float =
        if (speed in HOLD_SPEED_CHOICES) speed else 2f

    fun coerceDefaultSpeed(speed: Float): Float =
        if (speed in DEFAULT_SPEED_CHOICES) speed else 1f

    /** "2" for 2.0, "1.5" for 1.5, "0.75" for 0.75: how a speed is shown in labels. */
    fun formatSpeed(speed: Float): String =
        if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
}
