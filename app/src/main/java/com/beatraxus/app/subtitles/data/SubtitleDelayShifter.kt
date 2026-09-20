package com.beatraxus.app.subtitles.data

import java.io.File
import java.util.Locale

object SubtitleDelayShifter {

    private val TIMESTAMP_LINE_REGEX = Regex("^(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})")

    fun shiftDelay(srtContent: String, delayMs: Long): String {
        if (delayMs == 0L) return srtContent

        val lines = srtContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = StringBuilder()

        for (line in lines) {
            val match = TIMESTAMP_LINE_REGEX.find(line.trim())
            if (match != null) {
                val startMs = parseTimestampToMs(match.groupValues[1])
                val endMs = parseTimestampToMs(match.groupValues[2])

                val newStartMs = (startMs + delayMs).coerceAtLeast(0L)
                val newEndMs = (endMs + delayMs).coerceAtLeast(0L)

                val newStartStr = formatMsToTimestamp(newStartMs)
                val newEndStr = formatMsToTimestamp(newEndMs)

                result.append("$newStartStr --> $newEndStr\n")
            } else {
                result.append("$line\n")
            }
        }

        return result.toString().trimEnd()
    }

    fun getOrCreateShiftedFile(originalFile: File, delayMs: Long): File {
        if (delayMs == 0L) return originalFile

        val parentDir = originalFile.parentFile ?: return originalFile
        val baseName = originalFile.nameWithoutExtension
        val targetName = "${baseName}_delay_${delayMs}.srt"
        val targetFile = File(parentDir, targetName)

        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }

        // Clean up older delay files for this base subtitle
        parentDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("${baseName}_delay_") && file.name.endsWith(".srt") && file.name != targetName) {
                file.delete()
            }
        }

        val originalContent = originalFile.readText(Charsets.UTF_8)
        val shiftedContent = shiftDelay(originalContent, delayMs)
        targetFile.writeText(shiftedContent, Charsets.UTF_8)

        return targetFile
    }

    private fun parseTimestampToMs(timestamp: String): Long {
        val parts = timestamp.replace('.', ',').split(":", ",")
        if (parts.size >= 4) {
            val h = parts[0].toLongOrNull() ?: 0L
            val m = parts[1].toLongOrNull() ?: 0L
            val s = parts[2].toLongOrNull() ?: 0L
            val ms = parts[3].toLongOrNull() ?: 0L
            return (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms
        }
        return 0L
    }

    private fun formatMsToTimestamp(totalMs: Long): String {
        val clamped = totalMs.coerceAtLeast(0L)
        val ms = clamped % 1000
        val totalSeconds = clamped / 1000
        val s = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val m = totalMinutes % 60
        val h = totalMinutes / 60

        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }
}
