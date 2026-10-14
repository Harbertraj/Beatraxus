package com.beatraxus.app.engine.aieq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEqKnowledgeTest {

    private fun infer(genre: String = "", title: String = "Song", artist: String = "Artist", album: String = "Album", folder: String = "") =
        AiEqKnowledge.inferFromMetadata(genre, title, artist, album, folder)

    @Test fun tagDecidesGenre() {
        assertEquals("EDM", infer(genre = "Progressive House").curveKey)
        assertEquals("Hip-Hop", infer(genre = "Hip-Hop/Rap").curveKey)
        assertEquals("Metal", infer(genre = "Heavy Metal").curveKey)
        assertTrue(infer(genre = "Rock").known)
    }

    @Test fun junkTagsAreIgnored() {
        assertFalse(infer(genre = "Unknown").known)
        assertFalse(infer(genre = "(17)").known)
        assertEquals("Pop", infer(genre = "<unknown>").curveKey)
    }

    @Test fun titleWordsAreNotMisreadAsGenre() {
        // "House of Cards" is not house music; "Rap" must stand alone; "Warehouse" must not match "house".
        assertEquals("Pop", infer(title = "House of Cards").curveKey)
        assertEquals("Pop", infer(album = "Warehouse Sessions").curveKey)
        assertEquals("EDM", infer(title = "Summer (Club Remix)").curveKey)
    }

    @Test fun folderNameGivesGenre() {
        assertEquals("Classical", infer(folder = "/Music/Classical/Bach").curveKey)
    }

    @Test fun languageFromScript() {
        assertEquals("Tamil", AiEqKnowledge.detectLanguage("காற்று வெளியிடை"))
        assertEquals("Hindi", AiEqKnowledge.detectLanguage("तुम ही हो"))
        assertEquals("Korean", AiEqKnowledge.detectLanguage("사랑해"))
        assertEquals("English", AiEqKnowledge.detectLanguage("Hello World"))
    }

    @Test fun tamilLanguageDefaultsToIndianFilmCurve() {
        assertEquals("Indian Film", infer(title = "காற்று வெளியிடை").curveKey)
    }

    @Test fun moodFromTitleAndGenreDefault() {
        assertEquals("Sad", infer(title = "Goodbye Again").mood)
        assertEquals("Party", infer(genre = "EDM").mood)
        assertEquals("Sleep", infer(title = "Sleep Well Lullaby").mood)
        // 'war' inside another word must not trigger Aggressive
        assertEquals("Happy", infer(title = "Forward Motion").mood)
    }

    @Test fun gainsAreAlwaysTenAndClamped() {
        for (key in listOf("Pop", "EDM", "Hip-Hop", "Speech", "Lo-Fi", "Metal")) {
            val inf = AiEqKnowledge.Inferred(key, key, "Energetic", "English", true)
            val g = AiEqKnowledge.buildGains(inf, AiEqKnowledge.FileTraits(), null)
            assertEquals(AiEqKnowledge.BAND_COUNT, g.size)
            g.forEach { assertTrue(it in -AiEqKnowledge.MAX_GAIN_DB..AiEqKnowledge.MAX_GAIN_DB) }
        }
    }

    @Test fun differentGenresGiveDifferentCurves() {
        val edm = AiEqKnowledge.buildGains(AiEqKnowledge.Inferred("EDM", "EDM", "Party", "English", true), AiEqKnowledge.FileTraits(), null)
        val classical = AiEqKnowledge.buildGains(AiEqKnowledge.Inferred("Classical", "Classical", "Calm", "English", true), AiEqKnowledge.FileTraits(), null)
        assertTrue(AiEqKnowledge.maxDelta(edm, classical) > 1f)
        assertTrue(edm[0] > classical[0]) // EDM has more sub-bass
    }

    @Test fun lowBitrateLossyDoesNotBoostAir() {
        val inf = AiEqKnowledge.Inferred("Pop", "Pop", "Happy", "English", true)
        val low = AiEqKnowledge.buildGains(inf, AiEqKnowledge.FileTraits(year = 1970, bitrateKbps = 96, lossy = true), null)
        assertTrue(low[9] <= 0.3f)
    }

    @Test fun oldMasterGetsMoreAirThanNewOne() {
        val inf = AiEqKnowledge.Inferred("Pop", "Pop", "Happy", "English", true)
        val old = AiEqKnowledge.buildGains(inf, AiEqKnowledge.FileTraits(year = 1975), null)
        val new = AiEqKnowledge.buildGains(inf, AiEqKnowledge.FileTraits(year = 2020), null)
        assertTrue(old[8] > new[8])
    }

    @Test fun muffledRecordingGetsTrebleLift() {
        // spectrum falling off a cliff above the mids vs a normal tilted one
        val normal = FloatArray(128) { 1f / (1f + it / 16f) }
        val muffled = FloatArray(128) { if (it > 50) 0.001f else 1f / (1f + it / 16f) }
        fun corr(s: FloatArray) = AiEqKnowledge.spectralCorrection(
            AiEqKnowledge.Measured(s, -14f, 10f, 0.4f, 0.4f, 0.2f, 100f)
        )
        assertTrue(corr(muffled)[9] > corr(normal)[9])
    }

    @Test fun spectralCorrectionIsBounded() {
        val extreme = FloatArray(128) { if (it < 2) 100f else 0.0001f }
        AiEqKnowledge.spectralCorrection(AiEqKnowledge.Measured(extreme, -14f, 10f, 0.9f, 0.05f, 0.05f, 120f))
            .forEach { assertTrue(it in -2f..2f) }
    }

    @Test fun gainAtInterpolatesOnLogFrequency() {
        val g = FloatArray(10) { it.toFloat() } // band i has gain i
        assertEquals(0f, AiEqKnowledge.gainAt(g, 31.25f), 1e-4f)
        assertEquals(5f, AiEqKnowledge.gainAt(g, 1000f), 1e-4f)
        assertEquals(9f, AiEqKnowledge.gainAt(g, 20000f), 1e-4f)   // flat beyond the last band
        assertEquals(0f, AiEqKnowledge.gainAt(g, 20f), 1e-4f)      // flat below the first band
        assertEquals(5.5f, AiEqKnowledge.gainAt(g, 1414.2136f), 1e-3f) // half an octave above 1k
        assertEquals(0f, AiEqKnowledge.gainAt(FloatArray(3), 1000f), 0f)
    }
}
