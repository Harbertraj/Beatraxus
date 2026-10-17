package com.beatraxus.app.engine

import org.junit.Assert.*
import org.junit.Test

class AiEqCalculatorTest {

    @Test
    fun testAiEqClampingToFourDb() {
        // High target deltas should be strictly clamped to +/-4.0 dB
        val emptySpectrum = FloatArray(128) { 0.00001f }
        val result = AiEqCalculator.calculateAiEq(
            genre = "EDM",
            mood = "Energetic",
            spectral128 = emptySpectrum
        )

        assertEquals(10, result.bandGains.size)
        for (gain in result.bandGains) {
            assertTrue("Gain $gain should be <= 4.0 dB", gain <= 4.0f)
            assertTrue("Gain $gain should be >= -4.0 dB", gain >= -4.0f)
        }
    }

    @Test
    fun testPreampPreventsClipping() {
        val spectrum = FloatArray(128) { 0.01f }
        val result = AiEqCalculator.calculateAiEq(
            genre = "Rock",
            mood = "Energetic",
            spectral128 = spectrum
        )

        val maxGain = result.bandGains.maxOrNull() ?: 0.0f
        if (maxGain > 0.0f) {
            assertEquals("Preamp should equal -maxGain to prevent digital clipping", -maxGain, result.preampDb, 1e-3f)
        } else {
            assertEquals(0.0f, result.preampDb, 1e-3f)
        }
    }

    @Test
    fun testGenreTargetCurves() {
        val flatSpectrum = FloatArray(128) { 0.1f }
        val edmResult = AiEqCalculator.calculateAiEq(genre = "EDM", mood = "Calm", spectral128 = flatSpectrum)
        val rockResult = AiEqCalculator.calculateAiEq(genre = "Rock", mood = "Calm", spectral128 = flatSpectrum)

        // EDM should have higher sub-bass gain (31Hz / 62Hz) than flat/classical
        assertTrue("EDM low bass gain should be positive", edmResult.bandGains[0] > 0.0f)
        assertNotEquals(edmResult.bandGains[0], rockResult.bandGains[0], 0.1f)
    }

    @Test
    fun testGainSmoothingAcrossAdjacentBands() {
        val spectrum = FloatArray(128) { 0.1f }
        val result = AiEqCalculator.calculateAiEq(genre = "Hip-Hop", mood = "Energetic", spectral128 = spectrum)

        // Verify smooth transition between band 0 (31Hz) and band 1 (62Hz)
        val diff = Math.abs(result.bandGains[1] - result.bandGains[0])
        assertTrue("Adjacent band gain difference should be smooth (< 3.0 dB)", diff < 3.0f)
    }

    @Test
    fun testTamilGenreResolvesToNonFlatCurve() {
        val flatSpectrum = FloatArray(128) { 0.1f }
        val tamilResult = AiEqCalculator.calculateAiEq(genre = "Tamil", mood = "", spectral128 = flatSpectrum)

        // Tamil genre should resolve to Tamil Melody curve which has non-zero band targets
        val isNonFlat = tamilResult.bandGains.any { Math.abs(it) > 0.01f }
        assertTrue("Tamil genre should resolve to a non-flat curve", isNonFlat)
    }

    @Test
    fun testUnknownGenreStaysFlat() {
        val flatSpectrum = FloatArray(128) { 0.1f }
        val unknownResult = AiEqCalculator.calculateAiEq(genre = "UnknownGenreXYZ", mood = "", spectral128 = flatSpectrum)

        // Unknown genre should stay flat (all band gains = 0.0 dB for a flat input spectrum)
        for (gain in unknownResult.bandGains) {
            assertEquals("Unknown genre band gain should stay flat (0.0 dB)", 0.0f, gain, 1e-3f)
        }
    }

    @Test
    fun testAllOutputsStayWithinFourDb() {
        val testGenres = listOf(
            "Tamil", "Tamil Film Music", "Hindi", "EDM", "Hip-Hop", "Rock",
            "Pop", "Classical", "R&B", "Soul", "Lo-Fi", "Ambient", "Country", "Reggae", "UnknownGenre"
        )
        val testMoods = listOf("Energetic", "Calm", "Sad", "Happy", "Dark", "Romantic", "Uplifting", "Workout", "")
        val testSpectra = listOf(
            FloatArray(128) { 0.00001f },
            FloatArray(128) { 0.1f },
            FloatArray(128) { 10.0f },
            FloatArray(128) { i -> if (i < 10) 5.0f else 0.01f }
        )

        for (genre in testGenres) {
            for (mood in testMoods) {
                for (spectrum in testSpectra) {
                    val result = AiEqCalculator.calculateAiEq(
                        genre = genre,
                        mood = mood,
                        spectral128 = spectrum
                    )
                    assertEquals(10, result.bandGains.size)
                    for (gain in result.bandGains) {
                        assertTrue("Gain $gain for ($genre, $mood) must be <= 4.0 dB", gain <= 4.0f)
                        assertTrue("Gain $gain for ($genre, $mood) must be >= -4.0 dB", gain >= -4.0f)
                    }
                }
            }
        }
    }
}
