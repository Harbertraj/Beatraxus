package com.beatflowy.app.engine

import com.beatflowy.app.model.Song

data class AudioState(
    val sampleRate: Int = 44100,
    val outputSampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val codec: String = "",
    val bitrate: Int = 0,
    val outputPath: String = "AudioTrack",
    val outputDevice: String = "Speaker",
    val dynamicVolumeControlActive: Boolean = false,
    val resamplerActive: Boolean = false,
    val activeEffects: List<String> = emptyList(),
    val autoEqProfileName: String? = null,
    val outputLatencyMs: Int = 0,
    val underrunCount: Int = 0
)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val shuffleMode: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
)

enum class RepeatMode {
    OFF, ONE, ALL
}

enum class OutputMode(
    val title: String,
    val subtitle: String
) {
    STANDARD_AUDIO_TRACK(
        title = "Standard AudioTrack",
        subtitle = "Stable Android mixer path"
    ),
    HI_RES_DIRECT(
        title = "Hi-Res Direct Output",
        subtitle = "Direct DAC / Bluetooth route when supported"
    );

    companion object {
        fun fromName(value: String?): OutputMode {
            return entries.firstOrNull { it.name == value } ?: STANDARD_AUDIO_TRACK
        }
    }
}

data class OutputRouteState(
    val selectedMode: OutputMode = OutputMode.STANDARD_AUDIO_TRACK,
    val activeMode: OutputMode = OutputMode.STANDARD_AUDIO_TRACK,
    val outputDevice: String = "Speaker",
    val hiResDirectSupported: Boolean = false,
    val capabilitySummary: String = "Direct playback unavailable on this route",
    val maxSupportedSampleRate: Int = 48_000
)

enum class OutputDeviceType(val displayName: String) {
    SPEAKER("Speaker"),
    WIRED("Headphones"),
    BLUETOOTH("Bluetooth"),
    USB_DAC("USB DAC"),
    HDMI("HDMI"),
    UNKNOWN("Unknown")
}
