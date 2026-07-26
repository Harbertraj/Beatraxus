package com.beatraxus.app.repository

import com.beatraxus.app.model.LrcLine
import java.util.regex.Pattern

object LrcParser {
    // Fixed regex: support optional hours and 1-3 digit minutes
    private val TIME_PATTERN = Pattern.compile("\\[(?:(\\d+):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    fun parse(lrcContent: String?): List<LrcLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LrcLine>()
        val rawLines = lrcContent.lines()

        for (rawLine in rawLines) {
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isEmpty()) continue

            val matcher = TIME_PATTERN.matcher(trimmedLine)
            if (matcher.find()) {
                // If group 1 is null, it means there was no hour part, and group 2 is minutes
                val startTime = parseTime(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))
                val content = trimmedLine.substring(matcher.end()).trim()
                
                // Simplified cleanText
                val cleanText = content.replace(Regex("<[^>]+>"), "").trim()
                
                lines.add(LrcLine(startTime, cleanText))
            }
        }

        if (lines.isEmpty()) {
            // Restore the 3-second auto-advance fallback for plain lyrics
            return rawLines.filter { it.isNotBlank() }.mapIndexed { index, text ->
                LrcLine(index * 3000L, text.trim())
            }
        }

        return lines.sortedBy { it.startTime }
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
