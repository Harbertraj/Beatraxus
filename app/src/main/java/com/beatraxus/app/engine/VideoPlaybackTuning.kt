package com.beatraxus.app.engine

/**
 * How the video player picks a video decoder.
 *
 * - [HW]: hardware decoders only. Playback fails if the device has no hardware decoder for the stream.
 * - [HW_PLUS]: hardware decoders first, software decoders as a fallback (default).
 * - [SW]: software decoders only (falls back to any decoder if the device has no software one).
 */
enum class VideoDecoderMode(val label: String, val description: String) {
    HW("HW", "Hardware decoder only"),
    HW_PLUS("HW+", "Hardware first, software fallback"),
    SW("SW", "Software decoder only");

    companion object {
        val DEFAULT = HW_PLUS

        fun fromName(name: String?): VideoDecoderMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Settings the video renderers read while playing. Values can change at any time from the UI
 * thread and are read on the playback thread, hence the volatile fields.
 */
class VideoPlaybackTuning(
    decoderMode: VideoDecoderMode = VideoDecoderMode.DEFAULT,
    audioDelayMs: Int = 0
) {
    /** Read each time a video codec is created, so a change applies when the codec is re-created. */
    @Volatile
    var decoderMode: VideoDecoderMode = decoderMode

    /**
     * Audio delay in milliseconds. Positive values play the audio later than the video,
     * negative values play it earlier.
     */
    @Volatile
    var audioDelayMs: Int = clampAudioDelay(audioDelayMs)
        set(value) {
            field = clampAudioDelay(value)
        }

    /**
     * Automatic compensation (ms, >= 0) for the delay the FPS Booster adds to the picture. Set by the player while the
     * booster runs with A/V sync on, followed with a slew limit by [AudioDelayRenderer]; 0 otherwise. It is separate
     * from [audioDelayMs], which stays the user's own fine-tuning.
     */
    @Volatile
    var autoDelayTargetMs: Int = 0
        set(value) {
            field = value.coerceIn(0, AutoDelayRamp.MAX_MS)
        }

    /** The automatic compensation the clock really carries right now (written by [AudioDelayRenderer], read by anyone converting player positions). */
    @Volatile
    var appliedAutoDelayMs: Int = 0

    /** Total shift (ms) of the player's reported position against the media position: the user's delay plus the booster compensation. */
    val clockShiftMs: Int get() = audioDelayMs + appliedAutoDelayMs

    companion object {
        const val MIN_AUDIO_DELAY_MS = -5000
        const val MAX_AUDIO_DELAY_MS = 5000
        const val AUDIO_DELAY_STEP_MS = 50

        fun clampAudioDelay(ms: Int): Int = ms.coerceIn(MIN_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS)
    }
}
