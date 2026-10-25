package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import java.util.Locale
import kotlin.math.abs

/**
 * Turns what the speech service heard into subtitle cues placed on the video's timeline.
 *
 * Sync rules, in the order they matter:
 *  1. Every time the service returns is relative to the chunk of audio it was given, so [build] adds the chunk's
 *     start offset ([build]'s `offsetMs`) to every cue. The audio is cut at exact offsets (see FfmpegAudioArgs), so
 *     chunk time + offset is video time.
 *  2. Segment boundaries from Whisper can be late or early by up to a second; word timestamps are tighter. When words
 *     are available and agree with the segment, the cue starts at its first word and ends at its last word.
 *  3. Long segments are split at word boundaries, so a cue never stays on screen through a long stretch of speech.
 *  4. Cues never overlap, and a cue is never shorter than [MIN_CUE_MS] or longer than [MAX_CUE_MS].
 */
object GeneratedCueBuilder {

    const val MAX_LINE_CHARS = 42
    const val MAX_CUE_CHARS = 84
    const val MAX_CUE_MS = 7_000L
    const val MIN_CUE_MS = 500L

    /** A word may differ this much from its segment's edge and still be trusted to tighten it. */
    private const val WORD_SNAP_TOLERANCE_MS = 1_500L
    private const val WORD_GAP_SPLIT_MS = 1_000L
    private const val SENTENCE_BREAK_MIN_CHARS = 12
    private const val REPEAT_RUN_LIMIT = 4

    // Whisper's own thresholds for "this segment is silence or noise that it invented text for".
    private const val NO_SPEECH_THRESHOLD = 0.6
    private const val LOW_LOGPROB = -1.0
    private const val MAX_COMPRESSION_RATIO = 2.4

    /**
     * Cues for one chunk, moved by [offsetMs]. Not yet cleaned across chunks: call [polish] on the merged list.
     * [languageCode] decides whether long segments may be re-split by joining words with spaces.
     */
    fun build(transcript: WhisperTranscript, offsetMs: Long, languageCode: String?): List<SubtitleCue> {
        val segments = transcript.segments
        if (segments.isEmpty()) return emptyList()

        val buckets = assignWords(segments, transcript.words)
        val spaced = WhisperLanguages.usesSpaces(languageCode)
        val out = ArrayList<SubtitleCue>()

        segments.forEachIndexed { i, seg ->
            if (isHallucination(seg)) return@forEachIndexed
            val text = seg.text.trim()
            if (text.isEmpty()) return@forEachIndexed

            val words = buckets[i]
            // The words belong to this segment when they start and end near it. Whisper often runs a segment past its last
            // word (a pause before the next line), so the end is allowed to overshoot, and the start to come a little early.
            val wordsAgree = words.isNotEmpty() &&
                words.first().startMs >= seg.startMs - WORD_SNAP_TOLERANCE_MS &&
                words.first().startMs <= seg.endMs &&
                words.last().endMs >= seg.startMs &&
                words.last().endMs <= seg.endMs + WORD_SNAP_TOLERANCE_MS

            val start = if (wordsAgree) words.first().startMs else seg.startMs
            val end = if (wordsAgree) words.last().endMs else seg.endMs
            val tooLong = text.length > MAX_CUE_CHARS || end - start > MAX_CUE_MS

            if (wordsAgree && spaced && tooLong) {
                for (group in splitWords(words)) {
                    val groupText = group.joinToString(" ") { it.text }
                    out.add(SubtitleCue(group.first().startMs + offsetMs, group.last().endMs + offsetMs, groupText))
                }
            } else {
                out.add(SubtitleCue(start + offsetMs, end + offsetMs, text))
            }
        }
        return out
    }

    /**
     * Final clean-up of the cues of the whole video: ordering, minimum and maximum length, no overlap, no runs of the
     * same line repeated (a known way speech models fail on music and silence), and line wrapping.
     */
    fun polish(cues: List<SubtitleCue>, languageCode: String?): List<SubtitleCue> {
        val sorted = cues
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

        val deduped = dropRepeatedRuns(sorted)

        val lengthFixed = deduped.map { c ->
            var end = c.endMs
            if (end - c.startMs < MIN_CUE_MS) end = c.startMs + MIN_CUE_MS
            if (end - c.startMs > MAX_CUE_MS) end = c.startMs + MAX_CUE_MS
            c.copy(endMs = end)
        }

        // A cue ends where the next one starts rather than running over it.
        val result = ArrayList<SubtitleCue>(lengthFixed.size)
        for (i in lengthFixed.indices) {
            val c = lengthFixed[i]
            val next = lengthFixed.getOrNull(i + 1)
            val end = if (next != null && c.endMs > next.startMs) next.startMs else c.endMs
            if (end > c.startMs) result.add(c.copy(endMs = end))
        }

        val spaced = WhisperLanguages.usesSpaces(languageCode)
        return result.map { it.copy(text = if (spaced) wrap(it.text) else it.text.replace('\n', ' ').trim()) }
    }

    /** Words go to the segment whose time range they fall in; a word between two segments goes to the earlier one's half. */
    private fun assignWords(segments: List<WhisperSegment>, words: List<WhisperWord>): List<List<WhisperWord>> {
        val buckets = List(segments.size) { ArrayList<WhisperWord>() }
        if (words.isEmpty()) return buckets

        // boundary[i]: words starting before it belong to segment i (or an earlier one).
        val boundary = LongArray(segments.size)
        for (i in segments.indices) {
            boundary[i] = if (i == segments.lastIndex) Long.MAX_VALUE
            else (segments[i].endMs + segments[i + 1].startMs) / 2
            if (i > 0 && boundary[i] < boundary[i - 1]) boundary[i] = boundary[i - 1]
        }

        var si = 0
        for (w in words) {
            while (si < segments.lastIndex && w.startMs >= boundary[si]) si++
            buckets[si].add(w)
        }
        return buckets
    }

    private fun splitWords(words: List<WhisperWord>): List<List<WhisperWord>> {
        val groups = ArrayList<MutableList<WhisperWord>>()
        var current = ArrayList<WhisperWord>()
        var chars = 0

        for (w in words) {
            val len = w.text.length + 1
            val last = current.lastOrNull()
            val gap = if (last != null) w.startMs - last.endMs else 0L
            val duration = if (current.isNotEmpty()) w.endMs - current.first().startMs else 0L
            val shouldBreak = last != null && (
                chars + len > MAX_CUE_CHARS ||
                    gap > WORD_GAP_SPLIT_MS ||
                    duration > MAX_CUE_MS ||
                    (chars >= SENTENCE_BREAK_MIN_CHARS && endsSentence(last.text))
                )
            if (shouldBreak) {
                groups.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(w)
            chars += len
        }
        if (current.isNotEmpty()) groups.add(current)

        // A single dangling word reads badly: fold a very short last group into the one before it when it still fits.
        if (groups.size >= 2) {
            val tail = groups.last()
            val before = groups[groups.size - 2]
            val tailChars = tail.sumOf { it.text.length + 1 }
            val beforeChars = before.sumOf { it.text.length + 1 }
            val merged = tail.last().endMs - before.first().startMs
            if (tailChars < 8 && beforeChars + tailChars <= MAX_CUE_CHARS && merged <= MAX_CUE_MS) {
                before.addAll(tail)
                groups.removeAt(groups.lastIndex)
            }
        }
        return groups
    }

    private fun endsSentence(word: String): Boolean {
        val c = word.trimEnd().lastOrNull() ?: return false
        return c == '.' || c == '?' || c == '!' || c == '।' /* Devanagari danda */ || c == '。' || c == '？'
    }

    private fun isHallucination(seg: WhisperSegment): Boolean {
        val noSpeech = seg.noSpeechProb
        val logprob = seg.avgLogprob
        if (noSpeech != null && logprob != null && noSpeech > NO_SPEECH_THRESHOLD && logprob < LOW_LOGPROB) return true
        val ratio = seg.compressionRatio
        return ratio != null && ratio > MAX_COMPRESSION_RATIO
    }

    private fun dropRepeatedRuns(cues: List<SubtitleCue>): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>(cues.size)
        var i = 0
        while (i < cues.size) {
            val key = normalize(cues[i].text)
            var j = i + 1
            while (j < cues.size && normalize(cues[j].text) == key) j++
            val run = j - i
            if (run >= REPEAT_RUN_LIMIT) out.add(cues[i]) else for (k in i until j) out.add(cues[k])
            i = j
        }
        return out
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Breaks [text] into at most two lines of about [MAX_LINE_CHARS], at the space nearest the middle. */
    fun wrap(text: String): String {
        val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (flat.length <= MAX_LINE_CHARS) return flat

        val mid = flat.length / 2
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (i in flat.indices) {
            if (flat[i] == ' ' && abs(i - mid) < bestDistance) {
                best = i
                bestDistance = abs(i - mid)
            }
        }
        if (best < 0) return flat
        return flat.substring(0, best).trim() + "\n" + flat.substring(best + 1).trim()
    }
}

/** Writes cues as SubRip text, the format every subtitle in the app's cache is stored in. */
object SrtWriter {

    fun toSrt(cues: List<SubtitleCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { index, cue ->
            if (index > 0) sb.append('\n')
            sb.append(index + 1).append('\n')
            sb.append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
            sb.append(cue.text.trim()).append('\n')
        }
        return sb.toString()
    }

    fun timestamp(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        return String.format(
            Locale.ROOT, "%02d:%02d:%02d,%03d",
            t / 3_600_000L, (t / 60_000L) % 60, (t / 1_000L) % 60, t % 1_000L
        )
    }
}
