package com.beatflowy.app.engine

import com.beatflowy.app.model.Song

data class AudioState(
    val sampleRate: Int = 44100,
    val outputSampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val codec: String = "",
    val bitrate: Int = 0,
    val outputPath: String = "AudioTrack",
    val equalizerActive: Boolean = false,
    val dynamicVolumeControlActive: Boolean = false,
    val resamplerActive: Boolean = false,
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

enum class OutputMode {
    AAUDIO,
    OPENSLES,
    AUDIO_TRACK
}
