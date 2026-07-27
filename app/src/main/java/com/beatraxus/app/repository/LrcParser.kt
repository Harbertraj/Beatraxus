package com.beatraxus.app.repository

import com.beatraxus.app.model.LrcLine
import java.util.regex.Pattern

object LrcParser {
    // Fixed regex: support optional hours and 1-3 digit minutes
    private val TIME_PATTERN = Pattern.compile("\\[(?:(\\d+):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val WORD_TIME_PATTERN = Pattern.compile("<(?:(\\d+):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")

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
                val content = trimmedLine.substring(matcher.end())
                
                // Parse word timings
                val wordTimings = mutableListOf<com.beatraxus.app.model.WordTiming>()
                val wordMatcher = WORD_TIME_PATTERN.matcher(content)
                var lastIndex = 0
                var lastWordStartTime = startTime

                while (wordMatcher.find()) {
                    val wordText = content.substring(lastIndex, wordMatcher.start()).trim()
                    val wordEndTime = parseTime(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3), wordMatcher.group(4))
                    
                    if (wordText.isNotEmpty()) {
                        wordTimings.add(com.beatraxus.app.model.WordTiming(lastWordStartTime, maxOf(0L, wordEndTime - lastWordStartTime), wordText))
                    }
                    lastWordStartTime = wordEndTime
                    lastIndex = wordMatcher.end()
                }
                
                // Add remaining text after the last timestamp
                val remainingText = content.substring(lastIndex).trim()
                if (remainingText.isNotEmpty()) {
                    // We don't know the duration of the last word yet, but we'll approximate later or it ends with the line
                    wordTimings.add(com.beatraxus.app.model.WordTiming(lastWordStartTime, 0L, remainingText))
                }

                val cleanText = if (wordTimings.isNotEmpty()) {
                    wordTimings.joinToString(" ") { it.text }
                } else {
                    content.replace(Regex("<[^>]+>"), "").trim()
                }
                
                lines.add(LrcLine(startTime, cleanText, if (wordTimings.isNotEmpty()) wordTimings else null))
            }
        }

        if (lines.isEmpty()) {
            return rawLines.filter { it.isNotBlank() }.mapIndexed { index, text ->
                LrcLine(index * 3000L, text.trim(), null, 3000L)
            }
        }

        val sortedLines = lines.sortedBy { it.startTime }.toMutableList()
        
        // Calculate durations and refine word timings
        for (i in 0 until sortedLines.size) {
            val current = sortedLines[i]
            val nextStartTime = if (i < sortedLines.size - 1) sortedLines[i + 1].startTime else current.startTime + 5000L
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
                    sortedLines[i] = updatedLine.copy(wordTimings = refinedTimings)
                } else {
                    sortedLines[i] = updatedLine
                }
            } else {
                sortedLines[i] = updatedLine
            }
        }

        return sortedLines
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
