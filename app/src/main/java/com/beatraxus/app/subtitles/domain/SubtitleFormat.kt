package com.beatraxus.app.subtitles.domain

enum class SubtitleFormat {
    SRT,
    WEBVTT,
    ASS_SSA,
    SUB,
    UNSUPPORTED
}

object SubtitleFormatDetector {
    fun detect(content: String): SubtitleFormat {
        val trimmed = content.trimStart()

        if (trimmed.startsWith("WEBVTT", ignoreCase = true) || trimmed.contains("WEBVTT\n") || trimmed.contains("WEBVTT\r\n")) {
            return SubtitleFormat.WEBVTT
        }

        if (trimmed.contains("[Script Info]", ignoreCase = true) || trimmed.contains("[V4+ Styles]", ignoreCase = true) || trimmed.contains("Dialogue:", ignoreCase = true)) {
            return SubtitleFormat.ASS_SSA
        }

        if (Regex("\\{\\d+\\}\\{\\d+\\}").containsMatchIn(trimmed)) {
            return SubtitleFormat.SUB
        }

        if (Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(trimmed) ||
            Regex("\\d+\\r?\\n\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(trimmed)) {
            return SubtitleFormat.SRT
        }

        return SubtitleFormat.UNSUPPORTED
    }
}

object SubtitleConverter {

    fun convertToSrt(content: String, format: SubtitleFormat): String? {
        return when (format) {
            SubtitleFormat.SRT -> content
            SubtitleFormat.WEBVTT -> convertWebVttToSrt(content)
            SubtitleFormat.ASS_SSA -> convertAssToSrt(content)
            SubtitleFormat.SUB, SubtitleFormat.UNSUPPORTED -> null
        }
    }

    private fun convertWebVttToSrt(vttContent: String): String {
        val lines = vttContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = StringBuilder()
        var cueIndex = 1
        var inHeader = true

        val timestampRegex = Regex("^(\\d{2}:\\d{2}:\\d{2}|\\d{2}:\\d{2})\\.(\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}|\\d{2}:\\d{2})\\.(\\d{3})")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (inHeader) {
                if (line.isBlank() || line.startsWith("NOTE")) {
                    inHeader = false
                }
                i++
                continue
            }

            if (line.isBlank()) {
                i++
                continue
            }

            // Check if line is timestamp or cue ID
            var timeLine = line
            if (!timeLine.contains("-->") && i + 1 < lines.size && lines[i + 1].contains("-->")) {
                i++
                timeLine = lines[i].trim()
            }

            if (timeLine.contains("-->")) {
                val match = timestampRegex.find(timeLine)
                if (match != null) {
                    var startTime = match.groupValues[1]
                    if (!startTime.contains(":", ignoreCase = false) || startTime.length == 5) {
                        startTime = "00:$startTime"
                    }
                    val startMs = match.groupValues[2]

                    var endTime = match.groupValues[3]
                    if (!endTime.contains(":", ignoreCase = false) || endTime.length == 5) {
                        endTime = "00:$endTime"
                    }
                    val endMs = match.groupValues[4]

                    result.append("$cueIndex\n")
                    result.append("$startTime,$startMs --> $endTime,$endMs\n")
                    cueIndex++

                    i++
                    while (i < lines.size && lines[i].isNotBlank()) {
                        val textLine = lines[i].replace(Regex("<.*?>"), "") // Strip WebVTT HTML-like formatting
                        result.append("$textLine\n")
                        i++
                    }
                    result.append("\n")
                    continue
                }
            }

            i++
        }

        return result.toString().trim()
    }

    private fun convertAssToSrt(assContent: String): String {
        val lines = assContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = StringBuilder()
        var cueIndex = 1

        for (line in lines) {
            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val parts = line.substringAfter("Dialogue:").split(",", limit = 10)
                if (parts.size >= 10) {
                    val startRaw = parts[1].trim()
                    val endRaw = parts[2].trim()
                    val textRaw = parts[9]

                    val startSrt = parseAssTimestamp(startRaw)
                    val endSrt = parseAssTimestamp(endRaw)

                    val cleanText = textRaw.replace(Regex("\\{.*?\\}"), "") // strip ASS override tags
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .trim()

                    if (cleanText.isNotBlank()) {
                        result.append("$cueIndex\n")
                        result.append("$startSrt --> $endSrt\n")
                        result.append("$cleanText\n\n")
                        cueIndex++
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun parseAssTimestamp(assTime: String): String {
        // ASS format: H:MM:SS.cs e.g. 0:01:23.45
        val parts = assTime.split(":", ".")
        if (parts.size >= 4) {
            val hours = parts[0].padStart(2, '0')
            val minutes = parts[1].padStart(2, '0')
            val seconds = parts[2].padStart(2, '0')
            val cs = parts[3].padEnd(3, '0').substring(0, 3)
            return "$hours:$minutes:$seconds,$cs"
        }
        return "00:00:00,000"
    }
}
