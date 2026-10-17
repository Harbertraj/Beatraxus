package com.beatraxus.app.engine.aieq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AiEqMusicAnalysisTest {

    private val rate = 44100f / 2048f

    /** A 30 s onset curve with a pulse on every beat plus low-level noise. */
    private fun pulses(bpm: Float, seconds: Int = 30): FloatArray {
        val n = (rate * seconds).toInt()
        val rnd = Random(1)
        val nov = FloatArray(n) { rnd.nextFloat() * 0.2f }
        val period = rate * 60f / bpm
        var t = 0f
        while (t < n) { nov[t.toInt()] += 1f; t += period }
        return nov
    }

    @Test fun tempoIsFoundForCommonBpms() {
        for (bpm in listOf(70f, 100f, 120f, 140f)) {
            val t = AiEqMusicAnalysis.estimateTempo(pulses(bpm), rate)
            assertTrue("bpm $bpm not detected", t.known)
            assertEquals(bpm, t.bpm, 3f)
        }
    }

    @Test fun tempoIsDeterministic() {
        val a = AiEqMusicAnalysis.estimateTempo(pulses(120f), rate)
        val b = AiEqMusicAnalysis.estimateTempo(pulses(120f), rate)
        assertEquals(a, b)
    }

    @Test fun silenceOrShortClipHasNoTempo() {
        assertFalse(AiEqMusicAnalysis.estimateTempo(FloatArray(600), rate).known)
        assertFalse(AiEqMusicAnalysis.estimateTempo(FloatArray(20) { 1f }, rate).known)
        assertFalse(AiEqMusicAnalysis.estimateTempo(null, rate).known)
        assertFalse(AiEqMusicAnalysis.estimateTempo(pulses(120f), 0f).known)
    }

    private fun chromaOf(tonic: Int, minor: Boolean): FloatArray {
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val mn = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val p = if (minor) mn else major
        return FloatArray(12) { p[(it - tonic + 12) % 12].toFloat() }
    }

    @Test fun keyIsRecoveredForAllTonics() {
        for (tonic in 0 until 12) for (minor in listOf(false, true)) {
            val k = AiEqMusicAnalysis.estimateKey(chromaOf(tonic, minor))
            assertNotNull(k)
            assertEquals(tonic, k!!.tonic)
            assertEquals(minor, k.minor)
            assertTrue(k.confidence > 0.5f)
        }
    }

    @Test fun keyLabel() {
        assertEquals("A minor", AiEqMusicAnalysis.estimateKey(chromaOf(9, true))!!.label)
        assertEquals("C major", AiEqMusicAnalysis.estimateKey(chromaOf(0, false))!!.label)
    }

    @Test fun flatOrMissingChromaHasNoKey() {
        assertNull(AiEqMusicAnalysis.estimateKey(null))
        assertNull(AiEqMusicAnalysis.estimateKey(FloatArray(12)))
        assertNull(AiEqMusicAnalysis.estimateKey(FloatArray(5) { 1f }))
        val flat = AiEqMusicAnalysis.estimateKey(FloatArray(12) { 1f })
        assertTrue(flat == null || flat.confidence < 0.25f)
    }

    @Test fun tonicBassFrequencyIsInBassOctave() {
        for (t in 0 until 12) {
            val f = AiEqMusicAnalysis.tonicBassHz(t)
            assertTrue(f >= 40f && f < 80f)
        }
        assertEquals(55f, AiEqMusicAnalysis.tonicBassHz(9), 0.1f)
    }

    @Test fun musicAdjustmentIsSmallAndZeroWithoutEvidence() {
        assertTrue(AiEqKnowledge.musicAdjustment(AiEqKnowledge.MusicTraits()).all { it == 0f })
        val adj = AiEqKnowledge.musicAdjustment(
            AiEqKnowledge.MusicTraits(bpm = 170f, bpmConfidence = 1f, tonic = 2, minor = true, keyConfidence = 1f)
        )
        assertTrue(adj.all { it in -0.6f..0.6f })
        assertTrue(adj.any { it != 0f })
    }

    @Test fun differentTempoOrKeyGivesDifferentGains() {
        val inferred = AiEqKnowledge.inferFromMetadata("Pop", "Song", "Artist", "Album", "")
        val traits = AiEqKnowledge.FileTraits(year = 2020, bitrateKbps = 0, lossy = false)
        val slow = AiEqKnowledge.buildGains(inferred, traits, null,
            AiEqKnowledge.MusicTraits(bpm = 70f, bpmConfidence = 0.8f, tonic = 9, minor = true, keyConfidence = 0.8f))
        val fast = AiEqKnowledge.buildGains(inferred, traits, null,
            AiEqKnowledge.MusicTraits(bpm = 150f, bpmConfidence = 0.8f, tonic = 3, minor = false, keyConfidence = 0.8f))
        assertFalse(slow.contentEquals(fast))
        assertTrue(slow.all { Math.abs(it) <= AiEqKnowledge.MAX_GAIN_DB })
        assertTrue(fast.all { Math.abs(it) <= AiEqKnowledge.MAX_GAIN_DB })
    }
}
