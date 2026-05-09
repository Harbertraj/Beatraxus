package com.beatflowy.app.engine

import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.Song

data class AudioState(
    val songId: String? = null,
    val sampleRate: Int = 44100,
    val outputSampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val outputBitDepth: Int = 16,
    val codec: String = "",
    val bitrate: Int = 0,
    val outputPath: String = "AAudio",
    val outputDevice: String = "Speaker",
    val dynamicVolumeControlActive: Boolean = false,
    val resamplerActive: Boolean = false,
    val resamplerType: String = "SW",
    val activeEffects: List<String> = emptyList(),
    val autoEqProfileName: String? = null,
    val outputLatencyMs: Int = 0,
    val underrunCount: Int = 0,
    val pipelineSummary: String = ""
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

data class OutputRouteState(
    val selectedMode: OutputMode = OutputMode.AAUDIO,
    val activeMode: OutputMode = OutputMode.AAUDIO,
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
