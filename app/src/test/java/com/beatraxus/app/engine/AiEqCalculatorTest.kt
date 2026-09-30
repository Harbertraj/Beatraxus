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
}
