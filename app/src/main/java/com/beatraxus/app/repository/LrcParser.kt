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

    fun mapTagToSpeaker(tag: String): LyricSpeaker {
        val normalized = tag.lowercase()
        return when (normalized) {
            "male", "m", "he", "boy", "singer 1", "singer1", "s1", "v1", "ஆண்", "पुरुष" -> LyricSpeaker.MALE
            "female", "f", "she", "girl", "singer 2", "singer2", "s2", "v2", "பெண்", "महिला" -> LyricSpeaker.FEMALE
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
    private val OFFSET_PATTERN = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE)
    private val HEADER_PATTERN = Pattern.compile("^\\[(?:ar|ti|al|by|length|re|ve|kr):.*?\\]", Pattern.CASE_INSENSITIVE)

    fun parse(lrcContent: String?, songDurationMs: Long = 0L): List<LrcLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val rawLines = lrcContent.lines()

        // Extract [offset: +/-ms]
        var offsetMs = 0L
        for (rawLine in rawLines) {
            val offsetMatcher = OFFSET_PATTERN.matcher(rawLine.trim())
            if (offsetMatcher.find()) {
                offsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                break
            }
        }

        val lines = mutableListOf<LrcLine>()

        for (rawLine in rawLines) {
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isEmpty()) continue
            if (OFFSET_PATTERN.matcher(trimmedLine).matches()) continue
            if (HEADER_PATTERN.matcher(trimmedLine).matches()) continue

            // Anchored parsing: find all leading timestamps at start of line
            val timestamps = mutableListOf<Long>()
            var currIndex = 0

            while (currIndex < trimmedLine.length) {
                val matcher = TIME_PATTERN.matcher(trimmedLine)
                matcher.region(currIndex, trimmedLine.length)
                if (matcher.lookingAt()) {
                    val rawTime = parseTime(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))
                    // LRC spec: positive offset = lyrics appear earlier -> subtract offsetMs
                    val adjustedTime = maxOf(0L, rawTime - offsetMs)
                    timestamps.add(adjustedTime)
                    currIndex = matcher.end()
                } else {
                    break
                }
            }

            if (timestamps.isEmpty()) continue

            val rawContent = trimmedLine.substring(currIndex)
            val (speaker, content) = SpeakerDetector.detectAndStrip(rawContent)

            // Parse word timings relative to base time (t1 = timestamps[0])
            val t1 = timestamps[0]
            val baseWordTimings = mutableListOf<WordTiming>()
            val wordMatcher = WORD_TIME_PATTERN.matcher(content)
            var lastIndex = 0
            var lastWordStartTime = t1

            while (wordMatcher.find()) {
                val wordText = content.substring(lastIndex, wordMatcher.start()).trim()
                val wordEndTime = maxOf(0L, parseTime(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3), wordMatcher.group(4)) - offsetMs)

                if (wordText.isNotEmpty()) {
                    baseWordTimings.add(WordTiming(lastWordStartTime, maxOf(0L, wordEndTime - lastWordStartTime), wordText))
                }
                lastWordStartTime = wordEndTime
                lastIndex = wordMatcher.end()
            }

            val remainingText = content.substring(lastIndex).trim()
            if (remainingText.isNotEmpty()) {
                baseWordTimings.add(WordTiming(lastWordStartTime, 0L, remainingText))
            }

            val cleanText = if (baseWordTimings.isNotEmpty()) {
                baseWordTimings.joinToString(" ") { it.text }
            } else {
                content.replace(Regex("<[^>]+>"), "").trim()
            }

            for (ts in timestamps) {
                val timeShift = ts - t1
                val shiftedTimings = if (baseWordTimings.isNotEmpty()) {
                    baseWordTimings.map { it.copy(startTime = it.startTime + timeShift) }
                } else null

                lines.add(
                    LrcLine(
                        startTime = ts,
                        text = cleanText,
                        wordTimings = shiftedTimings,
                        speaker = speaker,
                        isTimed = true
                    )
                )
            }
        }

        // Untimed Plain Lyrics Fallback
        if (lines.isEmpty()) {
            val plainLines = rawLines.map { it.trim() }.filter {
                it.isNotEmpty() && !OFFSET_PATTERN.matcher(it).matches() && !HEADER_PATTERN.matcher(it).matches()
            }
            if (plainLines.isEmpty()) return emptyList()

            val lineDuration = if (songDurationMs > 0) maxOf(1000L, songDurationMs / plainLines.size) else 3000L

            return plainLines.mapIndexed { index, text ->
                val (speaker, cleanText) = SpeakerDetector.detectAndStrip(text)
                LrcLine(
                    startTime = index * lineDuration,
                    text = cleanText,
                    wordTimings = null,
                    duration = lineDuration,
                    speaker = speaker,
                    isTimed = false
                )
            }
        }

        // Original sorted list including blank markers
        val sortedLines = lines.sortedBy { it.startTime }.toMutableList()

        if (CARRY_FORWARD_SPEAKER) {
            var lastSpeaker = LyricSpeaker.NONE
            var lastNonBlankStartTime = -1L

            for (i in 0 until sortedLines.size) {
                val line = sortedLines[i]
                val isBlankOrInst = line.text.isBlank() ||
                        line.text.contains("♪") ||
                        line.text.equals("[Music]", ignoreCase = true) ||
                        line.text.equals("(Instrumental)", ignoreCase = true)

                val gapFromPrev = if (lastNonBlankStartTime >= 0) line.startTime - lastNonBlankStartTime else 0L

                if (line.speaker != LyricSpeaker.NONE) {
                    lastSpeaker = line.speaker
                    if (!isBlankOrInst) lastNonBlankStartTime = line.startTime
                } else if (isBlankOrInst) {
                    lastSpeaker = LyricSpeaker.NONE
                } else {
                    if (gapFromPrev > 6000L && (lastSpeaker == LyricSpeaker.CHORUS || lastSpeaker == LyricSpeaker.DUET_BOTH)) {
                        lastSpeaker = LyricSpeaker.NONE
                    }
                    if (lastSpeaker != LyricSpeaker.NONE) {
                        sortedLines[i] = line.copy(speaker = lastSpeaker)
                    }
                    lastNonBlankStartTime = line.startTime
                }
            }
        }

        // Calculate line durations & refine word timings on full sortedLines list BEFORE dropping blanks
        for (i in 0 until sortedLines.size) {
            val current = sortedLines[i]
            val next = if (i < sortedLines.size - 1) sortedLines[i + 1] else null
            val rawDuration = if (next != null) maxOf(0L, next.startTime - current.startTime) else 5000L
            val nextIsBlankOrEnd = (next == null || next.text.isBlank())

            // Cap line duration to sane max (10 s) when bounded by a blank marker or end of song
            val lineDuration = if (nextIsBlankOrEnd) minOf(rawDuration, 10000L) else rawDuration
            val updatedLine = current.copy(duration = lineDuration)

            val timings = updatedLine.wordTimings
            if (!timings.isNullOrEmpty()) {
                val lastTiming = timings.last()
                val maxAllowedWordEnd = minOf(next?.startTime ?: (current.startTime + lineDuration), current.startTime + lineDuration)
                val refinedLastWordDuration = maxOf(0L, maxAllowedWordEnd - lastTiming.startTime)

                val refinedTimings = timings.toMutableList()
                refinedTimings[timings.size - 1] = lastTiming.copy(duration = refinedLastWordDuration)
                sortedLines[i] = updatedLine.copy(wordTimings = refinedTimings)
            } else {
                sortedLines[i] = updatedLine
            }
        }

        // Drop blank lines AFTER duration calculations
        return sortedLines.filter { it.text.isNotBlank() }
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
