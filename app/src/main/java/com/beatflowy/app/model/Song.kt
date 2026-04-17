package com.beatflowy.app.model

import android.net.Uri

data class Song(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val sampleRateHz: Int,
    val bitDepth: Int = 16,
    val bitrate: Int = 0,
    val fileSizeBytes: Long = 0L,
    val albumArtUri: Uri? = null
)

enum class AudioOutputDevice(val displayName: String) {
    SPEAKER("Speaker"),
    WIRED("Headphones"),
    BLUETOOTH("Bluetooth"),
    USB_DAC("USB DAC"),
    UNKNOWN("Unknown")
}

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val isBuffering: Boolean = false,
    val resamplingEnabled: Boolean = true,
    val equalizerEnabled: Boolean = true,
    val inputSampleRate: Int = 44_100,
    val outputSampleRate: Int = 48_000,
    val outputDevice: String = AudioOutputDevice.SPEAKER.displayName,
    val eqGains: FloatArray = FloatArray(10) { 0f },
    val isLoadingLibrary: Boolean = false,
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerUiState) return false
        return currentSong == other.currentSong &&
                isPlaying == other.isPlaying &&
                progressMs == other.progressMs &&
                resamplingEnabled == other.resamplingEnabled &&
                equalizerEnabled == other.equalizerEnabled &&
                inputSampleRate == other.inputSampleRate &&
                outputSampleRate == other.outputSampleRate &&
                outputDevice == other.outputDevice &&
                eqGains.contentEquals(other.eqGains) &&
                isLoadingLibrary == other.isLoadingLibrary
    }
    override fun hashCode(): Int {
        var result = currentSong?.hashCode() ?: 0
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + eqGains.contentHashCode()
        return result
    }
}
