package com.beatflowy.app.engine

import com.beatflowy.app.model.Song

data class AudioState(
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val equalizerActive: Boolean = false
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
