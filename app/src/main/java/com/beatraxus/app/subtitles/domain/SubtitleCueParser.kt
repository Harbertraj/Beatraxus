package com.beatraxus.app.subtitles.domain

/** One timed line of subtitle text. Times are milliseconds from the start of the video. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Reads SRT text (the format every subtitle in the cache is stored in) into cues.
 *
 * Used by dual subtitles (video feature 22): ExoPlayer draws only one text track at a time, so the second subtitle is
 * parsed here and drawn by the player screen itself, in step with the playback position.
 */
object SubtitleCueParser {

    private val TIME_LINE = Regex(
        "((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[,.]\\d{1,3})"
    )
    private val MARKUP = Regex("<[^>]*>|\\{\\\\[^}]*\\}")

    fun parse(srt: String): List<SubtitleCue> {
        val lines = srt.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val match = TIME_LINE.find(lines[i].trim())
            if (match == null) {
                i++
                continue
            }
            val start = parseTimestamp(match.groupValues[1])
            val end = parseTimestamp(match.groupValues[2])
            i++

            val textLines = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (TIME_LINE.containsMatchIn(lines[i])) {
                    // A file with no blank line between cues: the number just collected belongs to the next cue.
                    if (textLines.isNotEmpty() && textLines.last().trim().all { it.isDigit() }) {
                        textLines.removeAt(textLines.lastIndex)
                    }
                    break
                }
                textLines.add(lines[i])
                i++
            }

            val text = textLines
                .joinToString("\n") { MARKUP.replace(it, "").trim() }
                .trim()
            if (text.isNotEmpty() && end > start) {
                cues.add(SubtitleCue(start, end, text))
            }
        }
        cues.sortBy { it.startMs }
        return cues
    }

    /** "00:01:02,345", "01:02.345" and "1:02:03.4" all work; the fraction is padded or cut to milliseconds. */
    internal fun parseTimestamp(raw: String): Long {
        val parts = raw.trim().split(':', ',', '.')
        val numbers = parts.map { it.toLongOrNull() ?: 0L }
        val ms = parts.last().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return when (parts.size) {
            4 -> numbers[0] * 3_600_000L + numbers[1] * 60_000L + numbers[2] * 1_000L + ms
            3 -> numbers[0] * 60_000L + numbers[1] * 1_000L + ms
            else -> 0L
        }
    }
}

/** Looks up which cue is on screen at a playback position. */
class SubtitleCueTimeline(cues: List<SubtitleCue>) {

    private val sorted: List<SubtitleCue> = cues.sortedBy { it.startMs }

    val size: Int get() = sorted.size

    /** Text on screen at [positionMs], or null between cues. When cues overlap, the one that started last wins. */
    fun textAt(positionMs: Long): String? {
        if (sorted.isEmpty() || positionMs < 0) return null

        // Last cue whose start is at or before the position.
        var low = 0
        var high = sorted.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null

        // A long cue can still be showing under several shorter ones that started after it.
        var index = found
        var checked = 0
        while (index >= 0 && checked < LOOK_BACK) {
            if (positionMs < sorted[index].endMs) return sorted[index].text
            index--
            checked++
        }
        return null
    }

    private companion object {
        const val LOOK_BACK = 8
    }
}
