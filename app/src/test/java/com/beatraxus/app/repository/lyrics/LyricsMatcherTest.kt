package com.beatraxus.app.repository.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatcherTest {
    @Test
    fun testNormalize() {
        val norm = LyricsMatcher.normalize("Song Title (feat. Artist) - Remastered 2020")
        assertEquals("songtitle", norm)

        val bracketNorm = LyricsMatcher.normalize("The End [Radio Edit]")
        assertEquals("theend", bracketNorm)
    }

    @Test
    fun testDurationHardGateInMatching() {
        // Duration mismatch > 8s -> fails confidence gate
        val queryTitle = "Hello"
        val queryArtist = "Adele"
        val queryDur = 200000L

        // 10s difference -> rejected by duration hard gate (> 8s)
        val candDur10sOff = 210000L
        assertFalse(LyricsMatcher.isConfidentMatch(queryTitle, queryArtist, queryDur, queryTitle, queryArtist, candDur10sOff))
        val score10sOff = LyricsMatcher.score(queryTitle, queryArtist, queryDur, queryTitle, queryArtist, candDur10sOff)
        assertTrue("Score for 10s off should be < 0.5, was $score10sOff", score10sOff < 0.5)

        // 2s difference -> accepted
        val candDur2sOff = 202000L
        assertTrue(LyricsMatcher.isConfidentMatch(queryTitle, queryArtist, queryDur, queryTitle, queryArtist, candDur2sOff))
    }

    @Test
    fun testVersionKeywords() {
        // Live vs studio -> rejected
        assertFalse(LyricsMatcher.isConfidentMatch("Hotel California (Live)", "Eagles", 300000L, "Hotel California", "Eagles", 300000L))

        // Remastered vs original -> accepted (remastered is ignored)
        assertTrue(LyricsMatcher.isConfidentMatch("Hotel California (Remastered 2013)", "Eagles", 300000L, "Hotel California", "Eagles", 300000L))

        // Acoustic vs Studio -> rejected
        assertFalse(LyricsMatcher.isConfidentMatch("Shape of You (Acoustic)", "Ed Sheeran", 200000L, "Shape of You", "Ed Sheeran", 200000L))
    }

    @Test
    fun testMissingDurationIsNeutral() {
        // Missing duration -> neutral (0.5 duration score), still passes confident match if title/artist match
        assertTrue(LyricsMatcher.isConfidentMatch("Hello", "Adele", 200000L, "Hello", "Adele", null))
        val scoreMissing = LyricsMatcher.score("Hello", "Adele", 200000L, "Hello", "Adele", null)
        assertTrue("Score with missing duration should be >= 0.8, was $scoreMissing", scoreMissing >= 0.8)
    }

    @Test
    fun testCleanTitle() {
        val cleaned = LyricsMatcher.cleanTitle("Marana Mass (From \"Petta\")")
        assertEquals("Marana Mass", cleaned)

        assertEquals("Petta", LyricsMatcher.cleanTitle("Petta (Original Motion Picture Soundtrack)"))
        assertEquals("Master", LyricsMatcher.cleanTitle("Master - Single"))
        assertEquals("Kutti Story", LyricsMatcher.cleanTitle("Kutti Story [Official Lyric Video]"))
    }

    @Test
    fun testPhoneticKeyVaathi() {
        val key1 = LyricsMatcher.phoneticKey("Vaathi Coming")
        val key2 = LyricsMatcher.phoneticKey("Vaadhi Coming")
        val key3 = LyricsMatcher.phoneticKey("Vathi Coming")

        assertEquals(key1, key2)
        assertEquals(key2, key3)
    }

    @Test
    fun testDifferentTamilTitlesScore() {
        val title1 = "மருதநாயகம்"
        val title2 = "பொன்னியின் செல்வன்"
        val score = LyricsMatcher.score(title1, "Artist", 200000L, title2, "Artist", 200000L)
        assertTrue("Score should be < 0.5 for different Tamil titles, was $score", score < 0.5)
    }

    @Test
    fun testArtistSimilarity() {
        val artist1 = "Anirudh Ravichander & S.P. Balasubrahmanyam"
        val artist2 = "Anirudh Ravichander"
        val sim = LyricsMatcher.artistSimilarity(artist1, artist2)
        assertTrue("Artist similarity should be >= 0.9, was $sim", sim >= 0.9)
    }
}
