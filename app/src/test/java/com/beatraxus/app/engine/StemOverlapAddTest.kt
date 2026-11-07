package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sin

class StemOverlapAddTest {
    @get:Rule val tmp = TemporaryFolder()

    private val seg = 1000
    private val hop = 750

    /** A fake engine: stem s is the input times (s + 1). Exact after a correct cross-fade whatever the overlap. */
    private fun scaled(stems: Int): (Array<FloatArray>, Int) -> Array<Array<FloatArray>> {
        val out = Array(stems) { Array(2) { FloatArray(seg) } }
        return { inChunk, n ->
            for (s in 0 until stems) for (ch in 0..1) for (i in 0 until n) out[s][ch][i] = inChunk[ch][i] * (s + 1)
            out
        }
    }

    private fun signal(frames: Int) = FloatArray(frames * 2) { i -> (sin(i * 0.013) * 0.6).toFloat() }

    private fun writePcm(a: FloatArray): File {
        val f = tmp.newFile()
        val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(a)
        f.writeBytes(bb.array())
        return f
    }

    private fun readPcm(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.nativeOrder())
        return FloatArray(bb.remaining() / 4).also { bb.asFloatBuffer().get(it) }
    }

    // ── chunk count ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun chunkCountMatchesTheLoop() {
        assertEquals(0, StemOverlapAdd.chunkCount(0, seg, hop))
        assertEquals(1, StemOverlapAdd.chunkCount(1, seg, hop))
        assertEquals(1, StemOverlapAdd.chunkCount(seg.toLong(), seg, hop))
        assertEquals(2, StemOverlapAdd.chunkCount(seg + 1L, seg, hop))
        assertEquals(2, StemOverlapAdd.chunkCount(seg + hop.toLong(), seg, hop))
        assertEquals(3, StemOverlapAdd.chunkCount(seg + hop + 1L, seg, hop))
    }

    // ── in memory, variable stem count ────────────────────────────────────────────────────────────────────

    @Test
    fun inMemoryLengthIsInputLengthFor4And6Stems() {
        for (stems in intArrayOf(4, 6)) {
            val pcm = signal(3333)
            val out = StemOverlapAdd.processInMemory(pcm, stems, seg, hop) { c, n -> scaled(stems)(c, n) }
            assertNotNull(out)
            assertEquals(stems, out!!.size)
            out.forEach { assertEquals(pcm.size, it.size) }
        }
    }

    @Test
    fun inMemoryConstantSignalStaysConstant() {
        for (stems in intArrayOf(4, 6)) {
            val pcm = FloatArray(2 * 2900) { 0.75f }
            val out = StemOverlapAdd.processInMemory(pcm, stems, seg, hop) { c, n -> scaled(stems)(c, n) }!!
            for (s in 0 until stems) for (v in out[s]) assertEquals(0.75f * (s + 1), v, 1e-4f)
        }
    }

    @Test
    fun weightsAreNormalisedSoAConstantModelOutputStaysConstant() {
        // A model that answers 1.0 everywhere: the sum of window weights differs per frame, the result must not.
        for (window in listOf(StemOverlapAdd.hannWindow(seg), StemOverlapAdd.trapezoidWindow(seg, seg - hop))) {
            val ones = Array(6) { Array(2) { FloatArray(seg) { 1f } } }
            val out = StemOverlapAdd.processInMemory(signal(4100), 6, seg, hop, window) { _, _ -> ones }!!
            out.forEach { stem -> stem.forEach { assertEquals(1f, it, 1e-5f) } }
        }
    }

    @Test
    fun inMemoryCancelAndFailureReturnNull() {
        assertNull(StemOverlapAdd.processInMemory(signal(3000), 4, seg, hop, isCancelled = { true }) { c, n -> scaled(4)(c, n) })
        assertNull(StemOverlapAdd.processInMemory(signal(3000), 4, seg, hop) { _, _ -> null })
    }

    // ── windows ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun windowsArePositiveAndTrapezoidIsFlatInTheMiddle() {
        val trap = StemOverlapAdd.trapezoidWindow(seg, seg - hop)
        assertEquals(seg, trap.size)
        assertTrue(trap.all { it > 0f && it <= 1f })
        assertEquals(1f, trap[seg / 2], 0f)
        assertTrue(trap[0] < trap[10])
        assertTrue(StemOverlapAdd.hannWindow(seg).all { it > 0f })
    }

    // ── streaming to files ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun streamingMatchesInMemoryForEveryLengthAndStemCount() {
        // short clip, exactly one segment, a boundary hit, and lengths that end mid hop.
        val lengths = intArrayOf(300, seg, seg + 1, seg + hop, seg + hop + 1, 2 * seg, 5 * hop + seg, 4321)
        for (stems in intArrayOf(4, 6)) {
            for (frames in lengths) {
                val pcm = signal(frames)
                val win = StemOverlapAdd.trapezoidWindow(seg, seg - hop)
                val expected = StemOverlapAdd.processInMemory(pcm, stems, seg, hop, win) { c, n -> scaled(stems)(c, n) }!!
                val outs = List(stems) { tmp.newFile() }
                val infer = scaled(stems)
                val chunks = StemOverlapAdd.separateFile(
                    writePcm(pcm), frames.toLong(), outs, seg, hop, stems, win
                ) { c, n -> infer(c, n) }
                assertEquals("chunks for $frames", StemOverlapAdd.chunkCount(frames.toLong(), seg, hop), chunks)
                for (s in 0 until stems) {
                    val got = readPcm(outs[s])
                    assertEquals("length stem $s of $frames", frames * 2, got.size)
                    for (i in got.indices) {
                        assertEquals("stem $s sample $i of $frames", expected[s][i], got[i], 1e-5f)
                        assertEquals(pcm[i] * (s + 1), got[i], 1e-4f)
                    }
                }
            }
        }
    }

    @Test
    fun streamingProgressCountsChunksAndEndsAtOne() {
        val frames = 5 * hop + seg
        val seen = ArrayList<StemOverlapAdd.Progress>()
        val infer = scaled(4)
        StemOverlapAdd.separateFile(
            writePcm(signal(frames)), frames.toLong(), List(4) { tmp.newFile() }, seg, hop,
            onProgress = { seen += it }
        ) { c, n -> infer(c, n) }
        assertEquals(StemOverlapAdd.chunkCount(frames.toLong(), seg, hop), seen.size)
        assertEquals((1..seen.size).toList(), seen.map { it.chunk })
        assertTrue(seen.all { it.totalChunks == seen.size })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
        assertEquals(1f, seen.last().fraction, 0f)
    }

    @Test
    fun streamingCancelThrowsAndFailedChunkCarriesTheEngineMessage() {
        val frames = 3 * seg
        val pcm = writePcm(signal(frames))
        try {
            StemOverlapAdd.separateFile(pcm, frames.toLong(), List(4) { tmp.newFile() }, seg, hop, isCancelled = { true }) { c, n -> scaled(4)(c, n) }
            fail("expected cancellation")
        } catch (_: CancellationException) {}
        try {
            StemOverlapAdd.separateFile(pcm, frames.toLong(), List(4) { tmp.newFile() }, seg, hop, errorMessage = { "model said no" }) { _, _ -> null }
            fail("expected failure")
        } catch (e: StemSeparationException) {
            assertEquals("model said no", e.message)
        }
    }

    @Test
    fun streamingSanitisesNonFiniteInputBeforeTheModelSeesIt() {
        val pcm = signal(2000)
        pcm[10] = Float.NaN; pcm[11] = Float.POSITIVE_INFINITY; pcm[500] = 1e9f
        var sawBad = false
        val infer = scaled(4)
        StemOverlapAdd.separateFile(writePcm(pcm), 2000, List(4) { tmp.newFile() }, seg, hop) { c, n ->
            for (ch in 0..1) for (i in 0 until n) if (!c[ch][i].isFinite() || abs(c[ch][i]) > StemOverlapAdd.INPUT_LIMIT) sawBad = true
            infer(c, n)
        }
        assertFalse(sawBad)
    }

    @Test
    fun streamingRewritesStemFilesFromScratch() {
        val outs = List(4) { tmp.newFile().also { f -> f.writeBytes(ByteArray(100_000) { 1 }) } }
        val infer = scaled(4)
        StemOverlapAdd.separateFile(writePcm(signal(500)), 500, outs, seg, hop) { c, n -> infer(c, n) }
        outs.forEach { assertEquals(500L * 8, it.length()) }
    }
}
