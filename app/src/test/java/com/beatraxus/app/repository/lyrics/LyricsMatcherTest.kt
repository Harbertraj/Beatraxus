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
    fun testIsMatch() {
        assertTrue(LyricsMatcher.isMatch("Hello", "Adele", 200000L, "Hello", "Adele", 201000L))

        // duration mismatch > 3s
        assertFalse(LyricsMatcher.isMatch("Hello", "Adele", 200000L, "Hello", "Adele", 205000L))

        // spelling difference but within similarity
        assertTrue(LyricsMatcher.isMatch("Helloo", "Adele", 200000L, "Hello", "Adele", 200000L))

        // one side duration unknown
        assertTrue(LyricsMatcher.isMatch("Hello", "Adele", 200000L, "Hello", "Adele", null))

        // total mismatch
        assertFalse(LyricsMatcher.isMatch("Rolling in the Deep", "Adele", 200000L, "Hello", "Adele", 200000L))
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
        // Two different Tamil titles
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
