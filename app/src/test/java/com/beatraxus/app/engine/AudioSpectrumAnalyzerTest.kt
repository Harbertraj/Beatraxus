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

    // ---- Authenticity logic (spectral edge + scoring) --------------------------------

    /** Builds a fake long-term power spectrum: gentle -dB/octave roll-off, optional brick wall. */
    private fun spectrum(nyquist: Int, wallHz: Int?): DoubleArray {
        val bins = 1024
        val bw = nyquist.toDouble() / bins
        return DoubleArray(bins) { i ->
            val f = (i + 0.5) * bw
            var db = -45.0 - 12.0 * kotlin.math.log10(1.0 + f / 1000.0) * 3.0 // natural roll-off
            if (wallHz != null && f > wallHz) db = -125.0                      // digital silence above the wall
            Math.pow(10.0, db / 10.0)
        }
    }

    private fun verdict(edge: AudioSpectrumAnalyzer.Companion.SpectralEdge, nyq: Int, padded: Boolean = false): Int =
        AudioSpectrumAnalyzer.scoreAuthenticity(edge, nyq, padded, mutableListOf())

    @Test
    fun fullBandCdIsOriginal() {
        val edge = AudioSpectrumAnalyzer.findSpectralEdge(spectrum(22050, null), 22050)
        assertFalse(edge.hasBrickWall)
        assertTrue(verdict(edge, 22050) >= 90)
    }

    @Test
    fun mp3StyleCutoffAt16kIsFlagged() {
        val edge = AudioSpectrumAnalyzer.findSpectralEdge(spectrum(22050, 16000), 22050)
        assertTrue(edge.hasBrickWall)
        assertTrue("edge ${edge.edgeHz}", edge.edgeHz in 15500..16500)
        assertTrue(verdict(edge, 22050) < 40)
    }

    @Test
    fun cutoffNear20kIsOnlyPossiblyUpscaled() {
        val edge = AudioSpectrumAnalyzer.findSpectralEdge(spectrum(22050, 20200), 22050)
        assertTrue(edge.hasBrickWall)
        val s = verdict(edge, 22050)
        assertTrue("score $s", s in 40..69)
    }

    @Test
    fun hiRes96kUpsampledFromCdIsFlagged() {
        val edge = AudioSpectrumAnalyzer.findSpectralEdge(spectrum(48000, 22050), 48000)
        assertTrue(edge.hasBrickWall)
        assertTrue(verdict(edge, 48000) < 40)
    }

    @Test
    fun genuineHiRes96kIsOriginal() {
        val edge = AudioSpectrumAnalyzer.findSpectralEdge(spectrum(48000, null), 48000)
        assertTrue(verdict(edge, 48000) >= 90)
    }

    @Test
    fun paddedBitDepthCapsScore() {
        val edge = AudioSpectrumAnalyzer.findSpectralEdge(spectrum(22050, null), 22050)
        assertTrue(verdict(edge, 22050, padded = true) <= 40)
    }
}
