package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import java.io.File

/**
 * Remembers every recognised chunk on disk, so a run that stopped (rate limit, no connection, the viewer left the
 * video) continues where it stopped instead of sending the same audio to the speech service again.
 *
 * The file only ever grows: one `chunk` line (index, spoken language, number of cues) followed by that many `cue` lines
 * (start, end, text). A write that was cut short leaves an incomplete last chunk, which [load] simply ignores.
 * What is stored is what was *heard*, before translation, so a different subtitle language reuses it too.
 */
object GenerationCheckpointStore {

    private const val CHUNK = "chunk"
    private const val CUE = "cue"

    fun append(file: File, result: ChunkResult) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append(CHUNK).append('\t').append(result.index).append('\t')
            .append(result.languageCode.orEmpty()).append('\t').append(result.cues.size).append('\n')
        for (c in result.cues) {
            sb.append(CUE).append('\t').append(c.startMs).append('\t').append(c.endMs).append('\t')
                .append(escape(c.text)).append('\n')
        }
        file.appendText(sb.toString(), Charsets.UTF_8)
    }

    /** Everything whole in [file], or null when there is nothing (yet). */
    fun load(file: File): GenerationCheckpoint? {
        if (!file.isFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val lines = text.split('\n').toMutableList()
        // The last element is "" when the file ends with a newline; anything else is a line that was cut short.
        lines.removeAt(lines.lastIndex)

        val done = LinkedHashSet<Int>()
        val cues = ArrayList<SubtitleCue>()
        var language: String? = null

        var i = 0
        while (i < lines.size) {
            val head = lines[i].split('\t', limit = 4)
            if (head.size != 4 || head[0] != CHUNK) break
            val index = head[1].toIntOrNull() ?: break
            val count = head[3].toIntOrNull() ?: break
            if (count < 0 || i + count > lines.size - 1) break

            val chunkCues = ArrayList<SubtitleCue>(count)
            var whole = true
            for (k in 1..count) {
                val parts = lines[i + k].split('\t', limit = 4)
                val start = parts.getOrNull(1)?.toLongOrNull()
                val end = parts.getOrNull(2)?.toLongOrNull()
                if (parts.size != 4 || parts[0] != CUE || start == null || end == null) {
                    whole = false
                    break
                }
                chunkCues += SubtitleCue(start, end, unescape(parts[3]))
            }
            if (!whole) break

            done += index
            cues += chunkCues
            if (language == null) language = head[2].takeIf { it.isNotBlank() }
            i += count + 1
        }
        return if (done.isEmpty()) null else GenerationCheckpoint(done, cues, language)
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(text[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/**
 * Decides when the subtitles recognised so far should be put in front of the player. Handing the player a new subtitle
 * file restarts its subtitle track, so it is done when it matters, not after every chunk:
 *  - the first time anything is ready,
 *  - when the part being watched is ready but not on screen yet (a seek, or generation started elsewhere),
 *  - when playback is about to run out of the part that is on screen and the next one is ready.
 */
object PartialSubtitleScheduler {

    /** How far ahead of the playing position a part must be ready, so the swap happens before the picture needs it. */
    const val LOOK_AHEAD_MS = 45_000L

    fun shouldShow(done: Set<Int>, shown: Set<Int>, positionMs: Long, chunkLengthMs: Long): Boolean {
        if (done.isEmpty() || chunkLengthMs <= 0L || done == shown) return false
        if (shown.isEmpty()) return true

        val here = (positionMs.coerceAtLeast(0L) / chunkLengthMs).toInt()
        val ahead = ((positionMs.coerceAtLeast(0L) + LOOK_AHEAD_MS) / chunkLengthMs).toInt()
        return (here in done && here !in shown) || (ahead in done && ahead !in shown)
    }
}
