package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.SubtitleCue
import com.beatraxus.app.subtitles.domain.SubtitleCueParser
import com.beatraxus.app.subtitles.domain.SubtitleCueTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCueParserTest {

    @Test
    fun parsesPlainSrt() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello there

            2
            00:01:02,250 --> 00:01:04,000
            Two lines
            of text
        """.trimIndent()

        val cues = SubtitleCueParser.parse(srt)

        assertEquals(
            listOf(
                SubtitleCue(1_000, 3_500, "Hello there"),
                SubtitleCue(62_250, 64_000, "Two lines\nof text")
            ),
            cues
        )
    }

    @Test
    fun handlesWindowsLineEndings() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nBye\r\n"
        assertEquals(listOf("Hi", "Bye"), SubtitleCueParser.parse(srt).map { it.text })
    }

    @Test
    fun stripsMarkupAndStyleTags() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i>Italic</i> and <font color=\"#fff\">colour</font> {\\an8}top\n"
        assertEquals("Italic and colour top", SubtitleCueParser.parse(srt).single().text)
    }

    @Test
    fun cueNumberIsNotPartOfThePreviousCueWhenBlankLineIsMissing() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n"
        assertEquals(listOf("First", "Second"), SubtitleCueParser.parse(srt).map { it.text })
    }

    @Test
    fun readsShortAndDottedTimestamps() {
        val vttLike = "00:01.500 --> 00:02.000\nShort form\n"
        val cue = SubtitleCueParser.parse(vttLike).single()
        assertEquals(1_500L, cue.startMs)
        assertEquals(2_000L, cue.endMs)

        assertEquals(3_723_400L, SubtitleCueParser.parseTimestamp("1:02:03.4"))
    }

    @Test
    fun skipsEmptyTextAndCuesThatEndBeforeTheyStart() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i></i>\n\n2\n00:00:05,000 --> 00:00:04,000\nBackwards\n\n3\n00:00:06,000 --> 00:00:07,000\nKept\n"
        assertEquals(listOf("Kept"), SubtitleCueParser.parse(srt).map { it.text })
    }

    @Test
    fun cuesComeBackSortedByStart() {
        val srt = "1\n00:00:09,000 --> 00:00:10,000\nLate\n\n2\n00:00:01,000 --> 00:00:02,000\nEarly\n"
        assertEquals(listOf("Early", "Late"), SubtitleCueParser.parse(srt).map { it.text })
    }

    @Test
    fun garbageGivesNoCues() {
        assertTrue(SubtitleCueParser.parse("not a subtitle file").isEmpty())
        assertTrue(SubtitleCueParser.parse("").isEmpty())
    }

    @Test
    fun timelineFindsTheCueOnScreen() {
        val timeline = SubtitleCueTimeline(
            listOf(
                SubtitleCue(1_000, 2_000, "A"),
                SubtitleCue(4_000, 6_000, "B")
            )
        )

        assertNull(timeline.textAt(0))
        assertNull(timeline.textAt(999))
        assertEquals("A", timeline.textAt(1_000))
        assertEquals("A", timeline.textAt(1_999))
        assertNull(timeline.textAt(2_000))
        assertNull(timeline.textAt(3_000))
        assertEquals("B", timeline.textAt(5_000))
        assertNull(timeline.textAt(6_000))
        assertNull(timeline.textAt(60_000))
        assertNull(timeline.textAt(-1))
    }

    @Test
    fun timelineKeepsALongCueVisibleUnderShortOnes() {
        val timeline = SubtitleCueTimeline(
            listOf(
                SubtitleCue(0, 10_000, "Long"),
                SubtitleCue(1_000, 2_000, "Short")
            )
        )

        assertEquals("Short", timeline.textAt(1_500))
        // The short cue is over, the long one is still showing.
        assertEquals("Long", timeline.textAt(3_000))
        assertNull(timeline.textAt(10_000))
    }

    @Test
    fun emptyTimelineNeverShowsAnything() {
        assertNull(SubtitleCueTimeline(emptyList()).textAt(1_000))
    }
}
