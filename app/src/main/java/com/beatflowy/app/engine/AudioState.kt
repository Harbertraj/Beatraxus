package com.beatflowy.app.engine

import com.beatflowy.app.model.Song

data class AudioState(
    val inputSampleRateHz: Int = 44_100,
    val outputSampleRateHz: Int = 48_000,
    val outputDevice: String = "Speaker",
    val resamplingActive: Boolean = true,
    val equalizerActive: Boolean = true,
    val bitDepth: Int = 16,
    val preampDb: Float = 0f,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val balance: Float = 0f,
    val stereoExpand: Float = 0f,
    val tempo: Float = 1f,
    val isMono: Boolean = false,
    val reverbMix: Float = 0f,
    val reverbSize: Float = 0.5f,
    val reverbDamp: Float = 0.5f,
    val reverbFilter: Float = 1.0f,
    val reverbFade: Float = 1.0f,
    val reverbPreDelay: Float = 0f,
    val reverbPreDelayMix: Float = 0f
)

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val shuffleMode: Boolean = false,
    val repeatMode: Int = 0, // 0: None, 1: One, 2: All
    val error: String? = null
)
