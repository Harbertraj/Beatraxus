package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedCueBuilderTest {

    private fun seg(start: Long, end: Long, text: String, noSpeech: Double? = null, logprob: Double? = null, ratio: Double? = null) =
        WhisperSegment(start, end, text, noSpeech, logprob, ratio)

    private fun word(start: Long, end: Long, text: String) = WhisperWord(start, end, text)

    @Test
    fun chunkOffsetMovesEveryCueOntoTheVideoTimeline() {
        val t = WhisperTranscript("english", listOf(seg(1_000, 3_000, "Hello"), seg(5_000, 6_000, "World")), emptyList())
        val cues = GeneratedCueBuilder.build(t, offsetMs = 1_200_000L, languageCode = "en")
        assertEquals(listOf(1_201_000L, 1_205_000L), cues.map { it.startMs })
        assertEquals(listOf(1_203_000L, 1_206_000L), cues.map { it.endMs })
    }

    @Test
    fun wordTimesTightenALooseSegment() {
        // The segment claims 0.0-4.0 s, but the words are spoken between 0.8 and 2.2 s.
        val t = WhisperTranscript(
            "english",
            listOf(seg(0, 4_000, "Good morning everyone")),
            listOf(word(800, 1_300, "Good"), word(1_300, 1_800, "morning"), word(1_800, 2_200, "everyone"))
        )
        val cue = GeneratedCueBuilder.build(t, 0, "en").single()
        assertEquals(800L, cue.startMs)
        assertEquals(2_200L, cue.endMs)
        assertEquals("Good morning everyone", cue.text)
    }

    @Test
    fun wordsThatDisagreeWithTheSegmentAreNotTrusted() {
        // Words more than 1.5 s away from the segment edges belong to something else: keep the segment's own times.
        val t = WhisperTranscript(
            "english",
            listOf(seg(10_000, 12_000, "Hello there")),
            listOf(word(14_500, 15_000, "stray"))
        )
        val cue = GeneratedCueBuilder.build(t, 0, "en").single()
        assertEquals(10_000L, cue.startMs)
        assertEquals(12_000L, cue.endMs)
    }

    @Test
    fun wordsAreGivenToTheSegmentTheyAreSpokenIn() {
        val t = WhisperTranscript(
            "english",
            listOf(seg(0, 2_000, "One two"), seg(3_000, 5_000, "Three four")),
            listOf(word(100, 600, "One"), word(700, 1_900, "two"), word(3_100, 3_700, "Three"), word(3_800, 4_900, "four"))
        )
        val cues = GeneratedCueBuilder.build(t, 0, "en")
        assertEquals(listOf(100L to 1_900L, 3_100L to 4_900L), cues.map { it.startMs to it.endMs })
    }

    @Test
    fun longSegmentIsSplitAtWordGapsAndSentenceEnds() {
        val words = listOf(
            word(0, 400, "This"), word(400, 800, "is"), word(800, 1_200, "the"), word(1_200, 1_700, "first"),
            word(1_700, 2_400, "sentence."),
            // 1.5 s of silence, then a second sentence
            word(3_900, 4_300, "And"), word(4_300, 4_700, "here"), word(4_700, 5_100, "is"), word(5_100, 5_800, "another."),
            word(5_900, 6_300, "Then"), word(6_300, 6_800, "a"), word(6_800, 7_500, "third")
        )
        val text = words.joinToString(" ") { it.text }
        val t = WhisperTranscript("english", listOf(seg(0, 7_500, text)), words)
        val cues = GeneratedCueBuilder.build(t, 0, "en")

        assertTrue("expected several cues, got ${cues.size}", cues.size >= 2)
        assertEquals(0L, cues.first().startMs)
        assertEquals(7_500L, cues.last().endMs)
        assertTrue(cues.all { it.endMs - it.startMs <= GeneratedCueBuilder.MAX_CUE_MS })
        assertTrue(cues.all { it.text.length <= GeneratedCueBuilder.MAX_CUE_CHARS })
        // The silence is not covered by any cue.
        assertTrue(cues.none { it.startMs < 3_000 && it.endMs > 3_800 })
        assertEquals(text, cues.joinToString(" ") { it.text })
    }

    @Test
    fun hindiSentencesBreakAtTheDanda() {
        val words = listOf(
            word(0, 500, "यह"), word(500, 1_000, "बहुत"), word(1_000, 1_600, "अच्छी"), word(1_600, 2_200, "फिल्म"),
            word(2_200, 2_900, "है।"),
            word(3_000, 3_500, "मुझे"), word(3_500, 4_000, "पसंद"), word(4_000, 4_600, "आई"),
            word(4_600, 5_200, "यह"), word(5_200, 5_900, "कहानी"), word(5_900, 7_600, "बहुत")
        )
        val text = words.joinToString(" ") { it.text }
        val t = WhisperTranscript("hindi", listOf(seg(0, 7_600, text)), words)
        val cues = GeneratedCueBuilder.build(t, 0, "hi")
        assertTrue(cues.size >= 2)
        assertEquals("यह बहुत अच्छी फिल्म है।", cues.first().text)
    }

    @Test
    fun languagesWrittenWithoutSpacesKeepTheSegmentAsIs() {
        val text = "これは長い文章です".repeat(12)
        val words = listOf(word(0, 3_000, "これは"), word(3_000, 9_000, "長い"))
        val t = WhisperTranscript("japanese", listOf(seg(0, 9_000, text)), words)
        val cues = GeneratedCueBuilder.build(t, 0, "ja")
        assertEquals(1, cues.size)
        assertEquals(text, cues.single().text)
    }

    @Test
    fun invented_textOverSilenceIsDropped() {
        val t = WhisperTranscript(
            "english",
            listOf(
                seg(0, 2_000, "Real speech", noSpeech = 0.02, logprob = -0.2),
                seg(2_000, 4_000, "Thanks for watching!", noSpeech = 0.9, logprob = -1.5),
                seg(4_000, 6_000, "la la la la la la la la", ratio = 3.1)
            ),
            emptyList()
        )
        assertEquals(listOf("Real speech"), GeneratedCueBuilder.build(t, 0, "en").map { it.text })
    }

    @Test
    fun noSpeechAloneDoesNotDropALine() {
        // High no_speech_prob with a confident transcription is real speech over background noise.
        val t = WhisperTranscript("english", listOf(seg(0, 2_000, "Quiet line", noSpeech = 0.8, logprob = -0.3)), emptyList())
        assertEquals(1, GeneratedCueBuilder.build(t, 0, "en").size)
    }

    @Test
    fun polishRemovesOverlapAndKeepsOrder() {
        val cues = listOf(
            SubtitleCue(5_000, 9_000, "second"),
            SubtitleCue(1_000, 6_000, "first")
        )
        val out = GeneratedCueBuilder.polish(cues, "en")
        assertEquals(listOf("first", "second"), out.map { it.text })
        assertEquals(5_000L, out[0].endMs)
        assertTrue(out.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test
    fun polishEnforcesMinimumAndMaximumLength() {
        val out = GeneratedCueBuilder.polish(
            listOf(SubtitleCue(0, 100, "blink"), SubtitleCue(10_000, 40_000, "never ends")),
            "en"
        )
        assertEquals(GeneratedCueBuilder.MIN_CUE_MS, out[0].endMs - out[0].startMs)
        assertEquals(GeneratedCueBuilder.MAX_CUE_MS, out[1].endMs - out[1].startMs)
    }

    @Test
    fun polishDropsALineRepeatedManyTimes() {
        val loop = (0 until 6).map { SubtitleCue(it * 2_000L, it * 2_000L + 1_500, "Thank you.") }
        val out = GeneratedCueBuilder.polish(loop + SubtitleCue(20_000, 21_000, "Different"), "en")
        assertEquals(listOf("Thank you.", "Different"), out.map { it.text })
    }

    @Test
    fun polishKeepsALineSaidTwice() {
        val two = listOf(SubtitleCue(0, 1_000, "No."), SubtitleCue(2_000, 3_000, "No."))
        assertEquals(2, GeneratedCueBuilder.polish(two, "en").size)
    }

    @Test
    fun wrapsLongLinesIntoTwoBalancedLines() {
        val wrapped = GeneratedCueBuilder.wrap("This is a rather long subtitle line that needs to be wrapped")
        val lines = wrapped.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.length <= GeneratedCueBuilder.MAX_LINE_CHARS })
        assertEquals("Short line", GeneratedCueBuilder.wrap("Short line"))
    }

    @Test
    fun srtOutputHasNumberedCuesAndCommaMilliseconds() {
        val srt = SrtWriter.toSrt(listOf(SubtitleCue(1_000, 3_500, "Hello"), SubtitleCue(3_723_004, 3_725_000, "Hour mark")))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,500\nHello\n\n2\n01:02:03,004 --> 01:02:05,000\nHour mark\n",
            srt
        )
    }
}
