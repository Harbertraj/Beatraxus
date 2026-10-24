package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.LyricsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRacePolicyTest {
    @Test
    fun `only a valid word-by-word result ends the race`() {
        assertTrue(LyricsRacePolicy.isDecisive(LyricsType.WORD_BY_WORD, true))
        assertFalse(LyricsRacePolicy.isDecisive(LyricsType.WORD_BY_WORD, false))
        assertFalse(LyricsRacePolicy.isDecisive(LyricsType.SYNCED, true))
        assertFalse(LyricsRacePolicy.isDecisive(LyricsType.PLAIN, true))
    }

    @Test
    fun `with nothing usable we wait for the rest of the deadline`() {
        assertEquals(9_000L, LyricsRacePolicy.waitMs(false, 9_000L))
    }

    @Test
    fun `once a timed result is held higher priority providers get only the grace period`() {
        assertEquals(LyricsRacePolicy.GRACE_MS, LyricsRacePolicy.waitMs(true, 9_000L))
        assertEquals(400L, LyricsRacePolicy.waitMs(true, 400L))
        assertEquals(0L, LyricsRacePolicy.waitMs(true, -5L))
    }

    @Test
    fun `timed types`() {
        assertTrue(LyricsRacePolicy.isTimed(LyricsType.SYNCED))
        assertTrue(LyricsRacePolicy.isTimed(LyricsType.WORD_BY_WORD))
        assertFalse(LyricsRacePolicy.isTimed(LyricsType.PLAIN))
    }
}
