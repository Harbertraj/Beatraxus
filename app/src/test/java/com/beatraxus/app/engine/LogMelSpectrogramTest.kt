package com.beatraxus.app.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class LogMelSpectrogramTest {

    @Test
    fun testMelConversionRoundTrip() {
        val melEngine = LogMelSpectrogram()
        val originalHz = 1000.0f
        val mel = melEngine.hzToMel(originalHz)
        val reconstructedHz = melEngine.melToHz(mel)
        assertEquals(originalHz, reconstructedHz, 1e-2f)
    }

    @Test
    fun testComputeFrom128BinSpectrum() {
        val melEngine = LogMelSpectrogram(numMelBins = 64)
        val spectral128 = FloatArray(128) { i -> (i + 1) * 0.01f }
        val melLog = melEngine.computeFrom128BinSpectrum(spectral128)

        assertEquals(64, melLog.size)
        for (value in melLog) {
            assertFalse("Mel log output should not be NaN", value.isNaN())
            assertFalse("Mel log output should not be Infinite", value.isInfinite())
        }
    }

    @Test
    fun testComputePcmSineWave() {
        val melEngine = LogMelSpectrogram(sampleRate = 16000, fftSize = 512, hopLength = 160, numMelBins = 64)
        // 0.5s of a 440 Hz sine wave
        val sampleRate = 16000
        val pcm = FloatArray(sampleRate / 2) { i ->
            sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat()
        }

        val logMel = melEngine.compute(pcm)
        assertTrue("LogMel frames should be generated", logMel.isNotEmpty())
        assertEquals(64, logMel[0].size)

        for (frame in logMel) {
            for (value in frame) {
                assertFalse("Mel frame value should not be NaN", value.isNaN())
                assertFalse("Mel frame value should not be Infinite", value.isInfinite())
            }
        }
    }
}
