package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import java.io.File

/**
 * Remembers every translated line on disk, so a translation that stopped (rate limit, no connection, the viewer left the
 * video) continues where it stopped instead of starting again at part 1.
 *
 * The first line is a signature of the subtitle being translated (line count and a hash of its text): when the source
 * changed, the saved lines no longer fit and are ignored. Every following line is `index <TAB> translated text`. The file
 * only ever grows; a last line cut short by a write that did not finish is ignored by [load].
 */
object TranslationCheckpointStore {

    private const val HEADER = "sig"

    fun signature(cues: List<SubtitleCue>): String =
        "${cues.size}:${cues.joinToString("\u0001") { it.text }.hashCode()}"

    fun append(file: File, signature: String, lines: Map<Int, String>) {
        if (lines.isEmpty()) return
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        if (!file.isFile || file.length() == 0L) sb.append(HEADER).append('\t').append(signature).append('\n')
        for ((index, text) in lines) {
            sb.append(index).append('\t').append(GenerationCheckpointStore.escape(text)).append('\n')
        }
        file.appendText(sb.toString(), Charsets.UTF_8)
    }

    /** The lines saved for a subtitle with this [signature]; empty when there is nothing (or it belongs to another source). */
    fun load(file: File, signature: String): Map<Int, String> {
        if (!file.isFile) return emptyMap()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyMap()
        }
        val rows = text.split('\n').toMutableList()
        // The last element is "" when the file ends with a newline; anything else is a line that was cut short.
        rows.removeAt(rows.lastIndex)
        val head = rows.firstOrNull()?.split('\t', limit = 2) ?: return emptyMap()
        if (head.size != 2 || head[0] != HEADER || head[1] != signature) return emptyMap()

        val out = HashMap<Int, String>()
        for (row in rows.drop(1)) {
            val parts = row.split('\t', limit = 2)
            val index = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val value = parts.getOrNull(1) ?: continue
            out[index] = GenerationCheckpointStore.unescape(value)
        }
        return out
    }

    fun clear(file: File) {
        if (file.exists()) file.delete()
    }
}
