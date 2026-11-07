package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StemProgressiveTest {

    @Test fun coverageMergesTouchingAndOverlappingRanges() {
        var c = StemCoverage.EMPTY.plus(10, 20).plus(30, 40)
        assertEquals(2, c.rangeCount)
        assertTrue(c.contains(12, 18))
        assertFalse(c.contains(15, 35))
        c = c.plus(20, 30)
        assertEquals(1, c.rangeCount)
        assertTrue(c.contains(10, 40))
        assertEquals(30L, c.coveredFrames)
    }

    @Test fun emptyAndFullCoverage() {
        assertFalse(StemCoverage.EMPTY.contains(0, 1))
        assertTrue(StemCoverage.EMPTY.contains(5, 5))
        assertTrue(StemCoverage.FULL.contains(0, 10_000_000L))
    }

    @Test fun alignedStartRoundsDownToTheChunkGrid() {
        assertEquals(0L, StemOverlapAdd.alignedStart(9300, 0, 875))
        assertEquals(0L, StemOverlapAdd.alignedStart(9300, 500, 875))
        assertEquals(3500L, StemOverlapAdd.alignedStart(9300, 4000, 875))
        assertEquals(0L, StemOverlapAdd.alignedStart(9300, 20_000, 875)) // outside the song
    }

    private fun pcmFile(frames: Int): Pair<File, FloatArray> {
        val rnd = java.util.Random(7)
        val pcm = FloatArray(frames * 2) { rnd.nextFloat() - 0.5f }
        val f = File.createTempFile("prog", ".pcm").also { it.deleteOnExit() }
        val bb = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(pcm)
        f.writeBytes(bb.array())
        return f to pcm
    }

    private fun readFloats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /** Starting mid-song gives the same stems as starting at 0, covers the whole song, and publishes parts as it goes. */
    @Test fun runFromTheMiddleCoversTheWholeSongAndMatchesAFullRun() {
        val seg = 1000; val hop = 875; val total = 9300
        val (input, pcm) = pcmFile(total)
        val res = Array(2) { Array(2) { FloatArray(seg) } }
        val infer = { c: Array<FloatArray>, n: Int ->
            for (s in 0 until 2) for (ch in 0..1) for (i in 0 until n) res[s][ch][i] = c[ch][i] * (s + 1)
            res
        }
        for (start in longArrayOf(0L, 4000L, 8000L)) {
            val files = List(2) { File.createTempFile("stm", ".pcm").also { it.deleteOnExit() } }
            var cov = StemCoverage.EMPTY
            var firstFrom = -1L
            val chunks = StemOverlapAdd.separateFile(
                input, total.toLong(), files, seg, hop,
                startFrame = start,
                onFlushed = { a, b -> if (firstFrom < 0) firstFrom = a; cov = cov.plus(a, b) }
            ) { c, n -> infer(c, n) }
            assertEquals(StemOverlapAdd.chunkCountFrom(total.toLong(), start, seg, hop), chunks)
            assertEquals(StemOverlapAdd.alignedStart(total.toLong(), start, hop), firstFrom)
            assertTrue(cov.contains(0, total.toLong()))
            for (s in 0 until 2) {
                val out = readFloats(files[s])
                assertEquals(total * 2, out.size)
                for (i in pcm.indices) assertEquals(pcm[i] * (s + 1), out[i], 1e-4f)
            }
        }
    }
}
