package com.beatraxus.app.repository.lyrics

data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val videoId: String? = null
)
