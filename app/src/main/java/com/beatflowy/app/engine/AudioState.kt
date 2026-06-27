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
    val headroomDb: Float = 0f,
    val latencyFrames: Int = 0,
    val ditherType: String = "None",
    val eqMode: String = "IIR",
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
    val selectedMode: OutputMode = OutputMode.HI_RES,
    val activeMode: OutputMode = OutputMode.HI_RES,
    val outputDevice: String = "Speaker",
    val hiResDirectSupported: Boolean = false,
    val capabilitySummary: String = "Direct playback unavailable on this route",
    val maxSupportedSampleRate: Int = 48_000,
    val usbExclusiveActive: Boolean = false,
    val usbDeviceName: String = "",
    val usbSupportedRates: List<Int> = emptyList(),
    val usbSupportedBitDepths: List<Int> = emptyList(),

    // MMAP
    val mmapSupported: Boolean = false,
    val mmapExclusiveActive: Boolean = false,
    val mmapActualBufferFrames: Int = 0,
    val mmapActualLatencyMs: Float = 0f
)

enum class OutputDeviceType(val displayName: String) {
    SPEAKER("Speaker"),
    WIRED("Headphones"),
    BLUETOOTH("Bluetooth"),
    USB_DAC("USB DAC"),
    HDMI("HDMI"),
    UNKNOWN("Unknown")
}
