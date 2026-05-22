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
            val wordTimings = parseWordTimings(content)
            val cleanText = content.replace(WORD_TIME_PATTERN.toRegex(), "").trim()

            when {
                timeMatches.isNotEmpty() -> {
                    for (startTime in timeMatches) {
                        lines.add(LrcLine(startTime, cleanText, wordTimings.takeIf { it.isNotEmpty() }))
                    }
                }
                wordTimings.isNotEmpty() -> {
                    lines.add(LrcLine(wordTimings.first().startTime, cleanText, wordTimings))
                }
            }
        }

        if (lines.isEmpty()) {
            return rawLines.filter { it.isNotBlank() }.mapIndexed { index, text ->
                LrcLine(index * 3000L, text.trim(), duration = 3000L)
            }
        }

        // 3. Sort and calculate durations for interpolation
        val sorted = lines.sortedBy { it.startTime }
        val result = mutableListOf<LrcLine>()
        
        for (i in sorted.indices) {
            val current = sorted[i]
            val nextTime = if (i < sorted.size - 1) sorted[i + 1].startTime else current.startTime + 5000L
            val duration = (nextTime - current.startTime).coerceAtLeast(0L)
            
            // Deduplicate: If multiple lines have same startTime, merge them or keep last
            if (result.isNotEmpty() && result.last().startTime == current.startTime) {
                continue
            }
            
            result.add(current.copy(duration = duration))
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

    private fun parseWordTimings(text: String): List<WordTiming> {
        val timings = mutableListOf<WordTiming>()
        val matcher = WORD_TIME_PATTERN.matcher(text)
        val tags = mutableListOf<Pair<Long, Int>>() // time to word start index

        while (matcher.find()) {
            tags.add(parseTime(matcher.group(1), matcher.group(2), matcher.group(3)) to matcher.end())
        }

        for (i in tags.indices) {
            val (startTime, wordStart) = tags[i]
            val nextWordStart = if (i < tags.lastIndex) tags[i + 1].second else text.length
            val wordText = text.substring(wordStart, nextWordStart).trim()
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
