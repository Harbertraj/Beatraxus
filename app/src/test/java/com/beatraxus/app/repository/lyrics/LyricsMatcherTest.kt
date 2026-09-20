package com.beatraxus.app.repository.lyrics

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
}
