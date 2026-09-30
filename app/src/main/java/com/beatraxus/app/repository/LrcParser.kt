package com.beatraxus.app.repository

import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.LyricSpeaker
import com.beatraxus.app.model.WordTiming
import java.util.regex.Pattern

data class SpeakerParseResult(
    val speaker: LyricSpeaker,
    val cleanContent: String
)

object SpeakerDetector {
    private val BRACKET_PATTERN = Pattern.compile("^(?:<[^>]+>\\s*)*(?:\\[|\\()\\s*([^\\)\\]]+?)\\s*(?:\\]|\\))\\s*(.*)$")
    private val COLON_PATTERN = Pattern.compile("^(?:<[^>]+>\\s*)*([A-Za-z0-9_\u0B80-\u0BFF\u0900-\u097F\\s]+?)\\s*:\\s*(.*)$")

    fun detectAndStrip(content: String): SpeakerParseResult {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return SpeakerParseResult(LyricSpeaker.NONE, content)

        val bracketMatcher = BRACKET_PATTERN.matcher(trimmed)
        if (bracketMatcher.find()) {
            val tag = bracketMatcher.group(1)?.trim() ?: ""
            val speaker = mapTagToSpeaker(tag)
            if (speaker != LyricSpeaker.NONE) {
                val leadingWordTags = extractLeadingWordTags(trimmed)
                val remainder = bracketMatcher.group(2) ?: ""
                return SpeakerParseResult(speaker, leadingWordTags + remainder.trimStart())
            }
        }

        val colonMatcher = COLON_PATTERN.matcher(trimmed)
        if (colonMatcher.find()) {
            val tag = colonMatcher.group(1)?.trim() ?: ""
            val speaker = mapTagToSpeaker(tag)
            if (speaker != LyricSpeaker.NONE) {
                val leadingWordTags = extractLeadingWordTags(trimmed)
                val remainder = colonMatcher.group(2) ?: ""
                return SpeakerParseResult(speaker, leadingWordTags + remainder.trimStart())
            }
        }

        return SpeakerParseResult(LyricSpeaker.NONE, content)
    }

    private fun extractLeadingWordTags(content: String): String {
        val matcher = Pattern.compile("^(?:<[^>]+>\\s*)+").matcher(content)
        return if (matcher.find()) matcher.group(0) ?: "" else ""
    }

    private fun mapTagToSpeaker(tag: String): LyricSpeaker {
        val normalized = tag.lowercase()
        return when (normalized) {
            "male", "m", "he", "boy", "singer 1", "v1", "ஆண்", "पुरुष" -> LyricSpeaker.MALE
            "female", "f", "she", "girl", "singer 2", "v2", "பெண்", "महिला" -> LyricSpeaker.FEMALE
            "both", "duet", "together", "all", "both/duet", "duet/both", "v1+v2", "v1 & v2" -> LyricSpeaker.DUET_BOTH
            "chorus", "hook", "refrain", "group", "choir" -> LyricSpeaker.CHORUS
            else -> LyricSpeaker.NONE
        }
    }
}

object LrcParser {
    const val CARRY_FORWARD_SPEAKER = true

    // Fixed regex: support optional hours and 1-3 digit minutes
    private val TIME_PATTERN = Pattern.compile("\\[(?:(\\d+):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    val WORD_TIME_PATTERN = Pattern.compile("<(?:(\\d+):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")

    fun parse(lrcContent: String?): List<LrcLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LrcLine>()
        val rawLines = lrcContent.lines()

        for (rawLine in rawLines) {
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isEmpty()) continue

            val matcher = TIME_PATTERN.matcher(trimmedLine)
            if (matcher.find()) {
                val startTime = parseTime(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))
                val rawContent = trimmedLine.substring(matcher.end())

                val (speaker, content) = SpeakerDetector.detectAndStrip(rawContent)

                // Parse word timings
                val wordTimings = mutableListOf<WordTiming>()
                val wordMatcher = WORD_TIME_PATTERN.matcher(content)
                var lastIndex = 0
                var lastWordStartTime = startTime

                while (wordMatcher.find()) {
                    val wordText = content.substring(lastIndex, wordMatcher.start()).trim()
                    val wordEndTime = parseTime(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3), wordMatcher.group(4))

                    if (wordText.isNotEmpty()) {
                        wordTimings.add(WordTiming(lastWordStartTime, maxOf(0L, wordEndTime - lastWordStartTime), wordText))
                    }
                    lastWordStartTime = wordEndTime
                    lastIndex = wordMatcher.end()
                }

                // Add remaining text after the last timestamp
                val remainingText = content.substring(lastIndex).trim()
                if (remainingText.isNotEmpty()) {
                    wordTimings.add(WordTiming(lastWordStartTime, 0L, remainingText))
                }

                val cleanText = if (wordTimings.isNotEmpty()) {
                    wordTimings.joinToString(" ") { it.text }
                } else {
                    content.replace(Regex("<[^>]+>"), "").trim()
                }

                lines.add(LrcLine(startTime, cleanText, if (wordTimings.isNotEmpty()) wordTimings else null, speaker = speaker))
            }
        }

        if (lines.isEmpty()) {
            return rawLines.filter { it.isNotBlank() }.mapIndexed { index, text ->
                val (speaker, cleanText) = SpeakerDetector.detectAndStrip(text.trim())
                LrcLine(index * 3000L, cleanText, null, 3000L, speaker = speaker)
            }
        }

        val sortedLines = lines.sortedBy { it.startTime }.toMutableList()

        if (CARRY_FORWARD_SPEAKER) {
            var lastSpeaker = LyricSpeaker.NONE
            for (i in 0 until sortedLines.size) {
                val line = sortedLines[i]
                val isInstrumentalOrBlank = line.text.isBlank() ||
                        line.text.contains("♪") ||
                        line.text.equals("[Music]", ignoreCase = true) ||
                        line.text.equals("(Instrumental)", ignoreCase = true)

                if (line.speaker != LyricSpeaker.NONE) {
                    lastSpeaker = line.speaker
                } else if (isInstrumentalOrBlank) {
                    lastSpeaker = LyricSpeaker.NONE
                } else if (lastSpeaker != LyricSpeaker.NONE) {
                    sortedLines[i] = line.copy(speaker = lastSpeaker)
                }
            }
        }

        val filteredLines = sortedLines.filter { it.text.isNotBlank() }.toMutableList()

        // Calculate durations and refine word timings
        for (i in 0 until filteredLines.size) {
            val current = filteredLines[i]
            val nextStartTime = if (i < filteredLines.size - 1) filteredLines[i + 1].startTime else current.startTime + 5000L
            val lineDuration = nextStartTime - current.startTime

            // Set line duration
            val updatedLine = current.copy(duration = lineDuration)

            // Refine last word duration if it's 0
            val timings = updatedLine.wordTimings
            if (!timings.isNullOrEmpty()) {
                val lastTiming = timings.last()
                if (lastTiming.duration == 0L) {
                    val refinedTimings = timings.toMutableList()
                    refinedTimings[timings.size - 1] = lastTiming.copy(duration = maxOf(0L, nextStartTime - lastTiming.startTime))
                    filteredLines[i] = updatedLine.copy(wordTimings = refinedTimings)
                } else {
                    filteredLines[i] = updatedLine
                }
            } else {
                filteredLines[i] = updatedLine
            }
        }

        return filteredLines
    }

    private fun parseTime(hour: String?, min: String, sec: String, ms: String?): Long {
        val h = hour?.toLong() ?: 0L
        val m = min.toLong()
        val s = sec.toLong()
        val msVal = when (ms?.length ?: 0) {
            1 -> ms!!.toLong() * 100
            2 -> ms!!.toLong() * 10
            3 -> ms!!.toLong()
            else -> 0L
        }
        return (h * 3600 + m * 60 + s) * 1000 + msVal
    }
}
