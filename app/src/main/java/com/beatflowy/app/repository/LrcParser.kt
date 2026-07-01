package com.beatflowy.app.repository

import com.beatflowy.app.model.LrcLine
import com.beatflowy.app.model.WordTiming
import java.util.regex.Pattern

object LrcParser {
    private val TIME_PATTERN = Pattern.compile("\\[(\\d{2,3}):(\\d{2})(?:[.:](\\d{2,3}))?\\]")
    private val WORD_TIME_PATTERN = Pattern.compile("<(\\d{2,3}):(\\d{2})(?:[.:](\\d{2,3}))?>")

    fun parse(lrcContent: String?): List<LrcLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LrcLine>()
        val rawLines = lrcContent.lines()

        for (rawLine in rawLines) {
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isEmpty()) continue

            // 1. Extract leading timestamps
            val timeMatches = mutableListOf<Long>()
            val matcher = TIME_PATTERN.matcher(trimmedLine)
            var lastIndex = 0
            while (matcher.find()) {
                if (matcher.start() > lastIndex + 1) break // Gap between timestamps
                timeMatches.add(parseTime(matcher.group(1), matcher.group(2), matcher.group(3)))
                lastIndex = matcher.end()
            }

            val content = trimmedLine.substring(lastIndex).trim()
            val wordTimings = parseWordTimings(content, timeMatches.firstOrNull() ?: 0L)
            val cleanText = content.replace(WORD_TIME_PATTERN.toRegex(), "").replace(TIME_PATTERN.toRegex(), "").trim()

            when {
                timeMatches.isNotEmpty() -> {
                    val baseStartTime = timeMatches.first()
                    for (startTime in timeMatches) {
                        val adjustedTimings = if (startTime == baseStartTime) {
                            wordTimings
                        } else {
                            wordTimings.map { it.copy(startTime = it.startTime - baseStartTime + startTime) }
                        }
                        lines.add(LrcLine(startTime, cleanText, adjustedTimings.takeIf { it.isNotEmpty() }))
                    }
                }
                wordTimings.isNotEmpty() -> {
                    lines.add(LrcLine(wordTimings.first().startTime, cleanText, wordTimings))
                }
            }
        }

        if (lines.isEmpty()) {
            return rawLines.filter { it.isNotBlank() }.mapIndexed { index, text ->
                val cleaned = text.replace(WORD_TIME_PATTERN.toRegex(), "").replace(TIME_PATTERN.toRegex(), "").trim()
                LrcLine(index * 3000L, cleaned, duration = 3000L)
            }
        }

        // 3. Sort and calculate durations for interpolation
        val sorted = lines.sortedBy { it.startTime }
        val result = mutableListOf<LrcLine>()
        
        for (i in sorted.indices) {
            val current = sorted[i]
            val nextTime = if (i < sorted.size - 1) sorted[i + 1].startTime else current.startTime + 5000L
            val lineDuration = (nextTime - current.startTime).coerceAtLeast(0L)
            
            // Deduplicate: If multiple lines have same startTime, merge them or keep last
            if (result.isNotEmpty() && result.last().startTime == current.startTime) {
                continue
            }

            // Adjust word timings to fill the line duration if it's ELRC
            val adjustedWordTimings = current.wordTimings?.let { timings ->
                if (timings.isNotEmpty()) {
                    val updatedTimings = timings.toMutableList()
                    val lastWord = updatedTimings.last()
                    val lastWordEnd = lastWord.startTime + lastWord.duration
                    val lineEndTime = current.startTime + lineDuration
                    
                    if (lastWordEnd < lineEndTime) {
                        // Extend the last word to the end of the line, 
                        // or at least give it a more reasonable duration
                        val newDuration = (lineEndTime - lastWord.startTime).coerceAtLeast(500L)
                        updatedTimings[updatedTimings.size - 1] = lastWord.copy(duration = newDuration)
                    }
                    updatedTimings
                } else null
            }
            
            result.add(current.copy(duration = lineDuration, wordTimings = adjustedWordTimings))
        }

        return result
    }

    private fun parseTime(min: String, sec: String, ms: String?): Long {
        val m = min.toLong()
        val s = sec.toLong()
        val msVal = when (ms?.length ?: 0) {
            1 -> ms!!.toLong() * 100
            2 -> ms!!.toLong() * 10
            3 -> ms!!.toLong()
            else -> 0L
        }
        return (m * 60 + s) * 1000 + msVal
    }

    private fun parseWordTimings(text: String, lineStartTime: Long): List<WordTiming> {
        val timings = mutableListOf<WordTiming>()
        val matcher = WORD_TIME_PATTERN.matcher(text)
        val tags = mutableListOf<Triple<Long, Int, Int>>() // time, start, end

        while (matcher.find()) {
            var startTime = parseTime(matcher.group(1), matcher.group(2), matcher.group(3))
            
            // Heuristic to handle relative word timestamps (some LRC variants use offsets from line start)
            if (startTime < lineStartTime && startTime < 600000) { // If tag is under 10min and before line start
                startTime += lineStartTime
            }
            
            tags.add(Triple(startTime, matcher.start(), matcher.end()))
        }

        if (tags.isEmpty()) return emptyList()

        // Ensure tags are sorted
        tags.sortBy { it.first }

        // Handle text BEFORE the first tag
        val firstTagStart = tags[0].second
        if (firstTagStart > 0) {
            val preText = text.substring(0, firstTagStart).trim()
            if (preText.isNotEmpty()) {
                val duration = (tags[0].first - lineStartTime).coerceAtLeast(0L)
                timings.add(WordTiming(lineStartTime, duration, preText))
            }
        }

        for (i in tags.indices) {
            val (startTime, _, tagEnd) = tags[i]
            val nextTagStart = if (i < tags.lastIndex) tags[i + 1].second else text.length
            val wordText = text.substring(tagEnd, nextTagStart).trim()
            val duration = if (i < tags.lastIndex) {
                (tags[i + 1].first - startTime).coerceAtLeast(0L)
            } else 500L

            if (wordText.isNotEmpty()) {
                timings.add(WordTiming(startTime, duration, wordText))
            }
        }
        return timings
    }
}
