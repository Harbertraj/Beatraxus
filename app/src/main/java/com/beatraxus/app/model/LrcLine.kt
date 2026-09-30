package com.beatraxus.app.model

enum class LyricSpeaker {
    NONE,
    MALE,
    FEMALE,
    DUET_BOTH,
    CHORUS
}

data class LrcLine(
    val startTime: Long,
    val text: String,
    val wordTimings: List<WordTiming>? = null,
    val duration: Long = 0L,
    val speaker: LyricSpeaker = LyricSpeaker.NONE
) {
    val time: Long get() = startTime
    val words: List<Word> get() = wordTimings?.map { Word(it.startTime, it.text, it.duration) } ?: emptyList()
}

data class WordTiming(
    val startTime: Long,
    val duration: Long,
    val text: String
)

data class Word(
    val time: Long,
    val text: String,
    val duration: Long = 0L
)
