package com.beatflowy.app.engine

import com.beatflowy.app.model.Song

data class AudioState(
    val inputSampleRateHz: Int = 44_100,
    val outputSampleRateHz: Int = 48_000,
    val outputDevice: String = "Speaker",
    val resamplingActive: Boolean = true,
    val equalizerActive: Boolean = true,
    val bitDepth: Int = 16
)

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val error: String? = null
)
