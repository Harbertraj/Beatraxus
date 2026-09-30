package com.beatraxus.app.repository.lyrics

import android.util.Log
import com.beatraxus.app.repository.LrcParser
import com.beatraxus.app.repository.LyricsType
import java.util.Locale

data class ValidationResult(
    val isValid: Boolean,
    val penalty: Double = 0.0,
    val reason: String? = null
)

object LyricsValidator {
    private const val TAG = "LyricsValidator"

    fun validate(
        content: String?,
        songDurationMs: Long = 0L,
        type: LyricsType = LyricsType.SYNCED
    ): ValidationResult {
        if (content.isNullOrBlank()) {
            return ValidationResult(isValid = false, penalty = 1.0, reason = "Empty lyrics content")
        }

        val lines = LrcParser.parse(content, songDurationMs)
        if (lines.isEmpty()) {
            return ValidationResult(isValid = false, penalty = 1.0, reason = "No lines parsed")
        }

        val timedLines = lines.filter { it.isTimed }

        // Plain lyrics (untimed)
        if (type == LyricsType.PLAIN || timedLines.isEmpty()) {
            if (lines.size < 2) {
                return ValidationResult(isValid = false, penalty = 1.0, reason = "Too few lines (< 2)")
            }
            return ValidationResult(isValid = true, penalty = 0.0, reason = null)
        }

        // Synced / Word-by-word
        if (timedLines.size < 4) {
            val reason = "Too few line count (${timedLines.size} < 4)"
            Log.d(TAG, "Validation failed: $reason")
            return ValidationResult(isValid = false, penalty = 1.0, reason = reason)
        }

        // Check monotonicity
        for (i in 0 until timedLines.size - 1) {
            if (timedLines[i + 1].startTime < timedLines[i].startTime) {
                val reason = "Non-monotonic line timestamps"
                Log.d(TAG, "Validation failed: $reason")
                return ValidationResult(isValid = false, penalty = 1.0, reason = reason)
            }
        }

        // Check duplicate timestamps (many lines sharing identical startTime)
        val startTimeCounts = timedLines.groupingBy { it.startTime }.eachCount()
        val maxIdentical = startTimeCounts.values.maxOrNull() ?: 0
        if (maxIdentical > 4 && maxIdentical.toDouble() / timedLines.size > 0.3) {
            val reason = "Too many lines ($maxIdentical) share identical timestamps"
            Log.d(TAG, "Validation failed: $reason")
            return ValidationResult(isValid = false, penalty = 1.0, reason = reason)
        }

        // Duration-based checks if song duration is known
        if (songDurationMs > 0) {
            val lastStart = timedLines.last().startTime

            // 1. Longer than the song (+ 10 s)
            if (lastStart > songDurationMs + 10_000L) {
                val reason = "Longer than the song"
                Log.d(TAG, "Validation failed: $reason")
                return ValidationResult(isValid = false, penalty = 1.0, reason = reason)
            }

            // 2. Far before the end
            val diffMs = songDurationMs - lastStart
            if (lastStart < 0.40 * songDurationMs) {
                val reason = "Ends too far before song (< 40%)"
                Log.d(TAG, "Validation failed: $reason")
                return ValidationResult(isValid = false, penalty = 1.0, reason = reason)
            }

            if (lastStart < 0.80 * songDurationMs) {
                val formattedDiff = formatDuration(diffMs)
                val warningMsg = "Ends $formattedDiff before the song"

                val penalty = when {
                    lastStart < 0.55 * songDurationMs -> 0.35
                    else -> 0.15
                }
                Log.d(TAG, "Validation warning: $warningMsg (penalty=$penalty)")
                return ValidationResult(isValid = true, penalty = penalty, reason = warningMsg)
            }
        }

        return ValidationResult(isValid = true, penalty = 0.0, reason = null)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }
}
