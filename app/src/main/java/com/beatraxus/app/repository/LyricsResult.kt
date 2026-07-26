package com.beatraxus.app.repository

enum class LyricsType {
    PLAIN,
    SYNCED,
    WORD_BY_WORD
}

data class LyricsResult(
    val type: LyricsType,
    val content: String,
    val score: Double = 0.0
)
