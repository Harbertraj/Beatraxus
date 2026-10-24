package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.SubtitleCue
import com.beatraxus.app.subtitles.domain.SubtitleCueTimeline
import com.beatraxus.app.subtitles.domain.SubtitleTapSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTapSyncTest {

    private val cues = listOf(
        SubtitleCue(1_000, 2_000, "one"),
        SubtitleCue(5_000, 6_000, "two"),
        SubtitleCue(9_000, 10_000, "three")
    )

    @Test
    fun delayMakesTheTappedLineAppearAtThePlayerPosition() {
        // Line "two" is in the file at 5.0 s but is heard at 7.5 s: the subtitles are 2.5 s early.
        assertEquals(2_500L, SubtitleTapSync.delayFor(cues[1], 7_500))
        // Heard at 4.0 s: the subtitles are 1 s late.
        assertEquals(-1_000L, SubtitleTapSync.delayFor(cues[1], 4_000))
        assertEquals(0L, SubtitleTapSync.delayFor(cues[1], 5_000))
    }

    @Test
    fun appliedDelayPutsTheTappedLineOnScreenAtThePlayerPosition() {
        val delay = SubtitleTapSync.delayFor(cues[1], 7_500)
        val timeline = SubtitleCueTimeline(SubtitleTapSync.shift(cues, delay))
        assertEquals("two", timeline.textAt(7_500))
        assertNull(timeline.textAt(5_500))
    }

    @Test
    fun indexNearIsTheLastCueThatHasStarted() {
        assertEquals(-1, SubtitleTapSync.indexNear(emptyList(), 1_000))
        assertEquals(0, SubtitleTapSync.indexNear(cues, 0))
        assertEquals(0, SubtitleTapSync.indexNear(cues, 1_000))
        assertEquals(0, SubtitleTapSync.indexNear(cues, 4_999))
        assertEquals(1, SubtitleTapSync.indexNear(cues, 5_000))
        assertEquals(2, SubtitleTapSync.indexNear(cues, 600_000))
    }

    @Test
    fun shiftMovesCuesAndLeavesThemAloneForZero() {
        assertEquals(cues, SubtitleTapSync.shift(cues, 0))
        val moved = SubtitleTapSync.shift(cues, 500)
        assertEquals(SubtitleCue(1_500, 2_500, "one"), moved[0])
        assertEquals(SubtitleCue(9_500, 10_500, "three"), moved[2])
    }

    @Test
    fun shiftDropsCuesPushedBeforeTheStartAndCutsTheOneThatStraddlesIt() {
        val moved = SubtitleTapSync.shift(cues, -1_500)
        // "one" (1.0-2.0 s) now ends at 0.5 s, so it starts at zero; "two" and "three" simply move up.
        assertEquals(SubtitleCue(0, 500, "one"), moved[0])
        assertEquals(SubtitleCue(3_500, 4_500, "two"), moved[1])

        val dropped = SubtitleTapSync.shift(cues, -2_500)
        assertEquals(listOf("two", "three"), dropped.map { it.text })
    }
}
