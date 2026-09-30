package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.LyricsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsValidatorTest {

    @Test
    fun testValidSyncedLyrics() {
        val lrc = """
            [00:10.00] Line 1
            [00:20.00] Line 2
            [00:30.00] Line 3
            [01:50.00] Line 4
            [02:40.00] Line 5
        """.trimIndent()

        val songDurMs = 180000L // 3 minutes (180s)
        val res = LyricsValidator.validate(lrc, songDurMs, LyricsType.SYNCED)

        assertTrue(res.isValid)
        assertEquals(0.0, res.penalty, 0.001)
    }

    @Test
    fun testTooFewLines() {
        val lrc = """
            [00:10.00] Line 1
            [00:20.00] Line 2
        """.trimIndent()

        val res = LyricsValidator.validate(lrc, 180000L, LyricsType.SYNCED)
        assertFalse(res.isValid)
        assertTrue(res.reason?.contains("Too few") == true)
    }

    @Test
    fun testNonMonotonicTimestamps() {
        val lrc = """
            [00:10.00] Line 1
            [00:20.00] Line 2
            [00:15.00] Line 3
            [00:30.00] Line 4
        """.trimIndent()

        val res = LyricsValidator.validate(lrc, 180000L, LyricsType.SYNCED)
        assertFalse(res.isValid)
        assertEquals("Non-monotonic line timestamps", res.reason)
    }

    @Test
    fun testLongerThanSong() {
        val lrc = """
            [00:10.00] Line 1
            [00:20.00] Line 2
            [00:30.00] Line 3
            [03:20.00] Line 4
        """.trimIndent()

        val songDurMs = 180000L // 3:00 (180s). Last line is at 3:20 (200s > 190s)
        val res = LyricsValidator.validate(lrc, songDurMs, LyricsType.SYNCED)

        assertFalse(res.isValid)
        assertEquals("Longer than the song", res.reason)
    }

    @Test
    fun testEndsFarBeforeSongWarning() {
        val lrc = """
            [00:10.00] Line 1
            [00:20.00] Line 2
            [00:30.00] Line 3
            [01:40.00] Line 4
        """.trimIndent()

        val songDurMs = 180000L // 3:00 (180s). Last line is at 1:40 (100s). Diff = 80s (1:20)
        val res = LyricsValidator.validate(lrc, songDurMs, LyricsType.SYNCED)

        assertTrue(res.isValid)
        assertTrue(res.penalty > 0.0)
        assertNotNull(res.reason)
        assertEquals("Ends 1:20 before the song", res.reason)
    }

    @Test
    fun testEndsLessThan40PercentHardReject() {
        val lrc = """
            [00:05.00] Line 1
            [00:10.00] Line 2
            [00:15.00] Line 3
            [00:20.00] Line 4
        """.trimIndent()

        val songDurMs = 180000L // 3:00 (180s). Last line is at 20s (< 40% of 180s = 72s)
        val res = LyricsValidator.validate(lrc, songDurMs, LyricsType.SYNCED)

        assertFalse(res.isValid)
        assertTrue(res.reason?.contains("< 40%") == true)
    }
}
