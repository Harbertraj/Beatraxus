package com.beatflowy.app.model

data class LrcLine(
    val time: Long,
    val text: String,
    val words: List<Word> = emptyList()
)

data class Word(
    val time: Long,
    val text: String,
    val duration: Long = 0L
)
