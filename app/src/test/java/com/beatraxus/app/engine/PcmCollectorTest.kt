package com.beatraxus.app.engine

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PCM that feeds the AI EQ audio measurement for formats the native extractor refuses (ALAC, Dolby, ...). */
class PcmCollectorTest {

    /** The collector never actually suspends, so a bare coroutine start is enough (no coroutines library needed). */
    private fun run(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { it.exceptionOrNull()?.let { e -> failure = e } })
        failure?.let { throw it }
    }

    private fun fmt(sr: Int = 44100, ch: Int = 2, dop: Boolean = false) = PcmAudioFormat(sr, ch, 16, null, dop)

    @Test fun stereoPassesThroughUnchanged() = run {
        val c = PcmCollector(1000)
        c.configure(fmt(48000, 2))
        c.write(floatArrayOf(0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f), 6)
        assertEquals(3, c.frames)
        assertEquals(2, c.outChannels)
        assertEquals(48000, c.sampleRate)
        assertArrayEquals(floatArrayOf(0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f), c.samples(), 0f)
    }

    @Test fun monoStaysMono() = run {
        val c = PcmCollector(1000)
        c.configure(fmt(ch = 1))
        c.write(floatArrayOf(0.5f, 0.25f), 2)
        assertEquals(1, c.outChannels)
        assertEquals(2, c.frames)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), c.samples(), 0f)
    }

    @Test fun writeHonoursSampleCountNotArraySize() = run {
        val c = PcmCollector(1000)
        c.configure(fmt())
        c.write(floatArrayOf(1f, 2f, 3f, 4f, 99f, 99f), 4)
        assertEquals(2, c.frames)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), c.samples(), 0f)
        c.write(floatArrayOf(1f), -5) // nonsense counts are ignored
        assertEquals(2, c.frames)
    }

    @Test fun stopsAtMaxFramesAndReportsFull() = run {
        val c = PcmCollector(4)
        c.configure(fmt())
        assertFalse(c.isFull())
        c.write(FloatArray(20) { it.toFloat() }, 20)
        assertEquals(4, c.frames)
        assertTrue(c.isFull())
        assertEquals(8, c.samples().size)
        c.write(FloatArray(10), 10) // still full: nothing more is kept
        assertEquals(4, c.frames)
    }

    @Test fun frameSplitAcrossWritesIsReassembled() = run {
        val c = PcmCollector(100)
        c.configure(fmt(ch = 2))
        c.write(floatArrayOf(1f, 2f, 3f), 3) // 1 frame + half a frame
        assertEquals(1, c.frames)
        c.write(floatArrayOf(4f, 5f, 6f), 3) // completes frame 2, then frame 3 is whole
        assertEquals(3, c.frames)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), c.samples(), 0f)
    }

    @Test fun oneSampleAtATimeStillWorks() = run {
        val c = PcmCollector(100)
        c.configure(fmt(ch = 2))
        for (v in 1..6) c.write(floatArrayOf(v.toFloat()), 1)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), c.samples(), 0f)
    }

    @Test fun surroundFoldsToStereoWithoutClipping() = run {
        val c = PcmCollector(100)
        c.configure(fmt(ch = 6))
        c.write(FloatArray(6) { 1f }, 6) // every channel at full scale
        assertEquals(1, c.frames)
        assertEquals(2, c.outChannels)
        val s = c.samples()
        assertTrue("downmix must not exceed full scale: ${s.toList()}", s[0] <= 1.0001f && s[1] <= 1.0001f)
        assertTrue(s[0] > 0.5f && s[1] > 0.5f)
        // Left-only content stays on the left.
        val c2 = PcmCollector(100)
        c2.configure(fmt(ch = 6))
        c2.write(floatArrayOf(0.8f, 0f, 0f, 0f, 0f, 0f), 6)
        assertTrue(c2.samples()[0] > 0.3f); assertEquals(0f, c2.samples()[1], 0f)
    }

    @Test fun downmixBoundsForEveryLayout() {
        for (ch in 1..8) {
            val (l, r) = PcmCollector.downmix(FloatArray(ch) { 1f }, ch)
            assertTrue("ch=$ch l=$l", l in 0.5f..1.0001f)
            assertTrue("ch=$ch r=$r", r in 0.5f..1.0001f)
        }
    }

    @Test fun absurdChannelCountsAreClamped() = run {
        val c = PcmCollector(100)
        c.configure(fmt(ch = 64))
        c.write(FloatArray(8) { 0.1f }, 8) // treated as 8 channels -> one frame
        assertEquals(1, c.frames)
        c.configure(fmt(ch = 0))
        assertEquals(1, c.outChannels)
        c.configure(fmt(sr = 0))
        assertEquals(44100, c.sampleRate)
    }

    @Test fun hasEnoughNeedsAFewThousandFrames() = run {
        val c = PcmCollector(100_000)
        c.configure(fmt())
        c.write(FloatArray(2 * (PcmCollector.MIN_FRAMES - 1)), 2 * (PcmCollector.MIN_FRAMES - 1))
        assertFalse(c.hasEnough())
        c.write(FloatArray(2), 2)
        assertTrue(c.hasEnough())
    }

    @Test fun dsdOverPcmIsIgnored() = run {
        val c = PcmCollector(100_000)
        c.configure(fmt(dop = true))
        c.write(FloatArray(20_000) { 0.3f }, 20_000)
        assertEquals(0, c.frames)
        assertFalse(c.hasEnough())
    }

    @Test fun reconfigureDropsHalfFrameFromEarlierFormat() = run {
        val c = PcmCollector(100)
        c.configure(fmt(ch = 2))
        c.write(floatArrayOf(1f, 2f, 3f), 3)
        c.configure(fmt(ch = 2))
        c.write(floatArrayOf(7f, 8f), 2)
        assertEquals(2, c.frames)
        assertArrayEquals(floatArrayOf(1f, 2f, 7f, 8f), c.samples(), 0f)
    }

    // ---- native result parsing ---------------------------------------------------------------------------

    private fun raw(spectral: Int, novelty: Int, size: Int = 15 + spectral + novelty) =
        FloatArray(size) { it.toFloat() }.also { if (size > 14) { it[13] = spectral.toFloat(); it[14] = novelty.toFloat() } }

    @Test fun parsesTheNativeLayout() {
        val f = parseNativeFeatures(raw(3, 2))
        assertNotNull(f)
        f!!
        assertEquals(0f, f.lufs, 0f); assertEquals(1f, f.rms, 0f); assertEquals(2f, f.peak, 0f)
        assertEquals(3f, f.dynamicRange, 0f); assertEquals(8f, f.tempoBpm, 0f)
        assertEquals(9f, f.truePeakDb, 0f); assertEquals(10f, f.clippedSamplePct, 0f)
        assertEquals(11f, f.freqRangeLowHz, 0f); assertEquals(12f, f.freqRangeHighHz, 0f)
        assertArrayEquals(floatArrayOf(15f, 16f, 17f), f.spectralData, 0f)
        assertArrayEquals(floatArrayOf(18f, 19f), f.noveltyVector!!, 0f)
    }

    @Test fun zeroNoveltyGivesNullVector() {
        assertNull(parseNativeFeatures(raw(4, 0))!!.noveltyVector)
    }

    @Test fun rejectsBrokenArrays() {
        assertNull(parseNativeFeatures(null))
        assertNull(parseNativeFeatures(FloatArray(14)))
        assertNull(parseNativeFeatures(raw(10, 10, size = 20)))   // lengths larger than the array
        assertNull(parseNativeFeatures(raw(-1, 2, size = 30)))    // negative length
        assertNull(parseNativeFeatures(raw(2, -3, size = 30)))
    }
}
