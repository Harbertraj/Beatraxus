package com.beatraxus.app.engine

import org.junit.Assert.*
import org.junit.Test

class AudioSpectrumAnalyzerTest {

    @Test
    fun testDetectSpectralCutoff_FullBandwidth() {
        val buckets = 128
        val accum = DoubleArray(buckets) { 1.0 } // Flat spectrum
        val cutoff = AudioSpectrumAnalyzer.detectSpectralCutoff(accum, 1, 22050)
        assertEquals(22050, cutoff)
    }

    @Test
    fun testDetectSpectralCutoff_SharpCliff() {
        val buckets = 128
        val nyquist = 22050
        val hzPerBucket = nyquist.toDouble() / buckets
        // Cutoff at ~16kHz
        val cutoffBucket = (16000 / hzPerBucket).toInt()
        val accum = DoubleArray(buckets) { b ->
            if (b <= cutoffBucket) 1.0 else 1e-9
        }
        val cutoff = AudioSpectrumAnalyzer.detectSpectralCutoff(accum, 1, nyquist)
        // Should be around 16kHz
        assertTrue("Cutoff $cutoff should be near 16000", cutoff in 15500..16500)
    }

    @Test
    fun testIsSuspiciousCutoff() {
        // Normal 44.1kHz (Nyquist 22050), cutoff 21000 -> not suspicious (ratio > 0.92)
        assertFalse(AudioSpectrumAnalyzer.isSuspiciousCutoff(21000, 22050))
        
        // Cutoff 16000 -> suspicious
        assertTrue(AudioSpectrumAnalyzer.isSuspiciousCutoff(16000, 22050))
        
        // High-res 96kHz (Nyquist 48000), cutoff 22050 -> not suspicious by the < 21500 rule
        // (Most lossy encoders don't go this high, so 22kHz content in a 96kHz container 
        // is likely "real" or at least not a simple MP3 transcode).
        assertFalse(AudioSpectrumAnalyzer.isSuspiciousCutoff(22050, 48000))
    }

    @Test
    fun testDetectBitDepthPadding_Padded() {
        val histogram = IntArray(256)
        histogram[0] = 900 // 90% are 0
        histogram[128] = 100
        assertTrue(AudioSpectrumAnalyzer.detectBitDepthPadding(histogram, 1000, 24))
    }

    @Test
    fun testDetectBitDepthPadding_NotPadded() {
        val histogram = IntArray(256) { 4 } // Uniform distribution
        assertFalse(AudioSpectrumAnalyzer.detectBitDepthPadding(histogram, 1024, 24))
    }

    @Test
    fun testDetectBitDepthPadding_16Bit() {
        val histogram = IntArray(256)
        histogram[0] = 1000
        // Should return false regardless of histogram if declared bit depth is 16
        assertFalse(AudioSpectrumAnalyzer.detectBitDepthPadding(histogram, 1000, 16))
    }
}
