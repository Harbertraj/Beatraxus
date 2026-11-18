package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin

class StemResamplerTest {

    private val srcRate = 44_100.0

    /** Stereo test tone at the source rate: left = sine, right = the same sine at half amplitude. */
    private fun tone(f0: Double, frames: Int): FloatArray {
        val x = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (0.5 * sin(2.0 * PI * f0 * i / srcRate)).toFloat()
            x[i * 2] = v
            x[i * 2 + 1] = v * 0.5f
        }
        return x
    }

    /**
     * Resamples [buffers] consecutive buffers the way StemMixerProcessor does (exact position, carried fraction) and
     * returns the error against the ideal tone, in dB relative to the signal (RMS, left channel).
     */
    private fun errorDb(f0: Double, outRate: Int, startFrame: Long = 123_457L, buf: Int = 1024, buffers: Int = 8): Double {
        val ratio = srcRate / outRate
        val rs = StemResampler(ratio)
        val x = tone(f0, 44_100 * 8)
        val dst = FloatArray(buf * 2)
        var cur = startFrame
        var errSq = 0.0
        var sigSq = 0.0
        repeat(buffers) {
            val exact = cur * ratio
            val base = floor(exact).toLong()
            val frac0 = exact - base
            val need = rs.framesNeeded(frac0, buf)
            val start = (base - rs.padLeft).toInt()
            rs.process(x.copyOfRange(start * 2, (start + need) * 2), frac0, dst, buf)
            for (i in 0 until buf) {
                val ideal = 0.5 * sin(2.0 * PI * f0 * (cur + i) * ratio / srcRate)
                val e = dst[i * 2] - ideal
                errSq += e * e
                sigSq += ideal * ideal
                // right channel must follow left at exactly half amplitude
                assertEquals(dst[i * 2] * 0.5f, dst[i * 2 + 1], 1e-5f)
            }
            cur += buf
        }
        return 10.0 * log10(errSq / sigSq)
    }

    @Test
    fun tonesAreReproducedBelowMinus80DbAt48k() {
        for (f in doubleArrayOf(1_000.0, 5_000.0, 10_000.0, 15_000.0)) {
            val db = errorDb(f, 48_000)
            assertTrue("48 kHz, $f Hz: error $db dB", db < -80.0)
        }
    }

    @Test
    fun tonesAreReproducedBelowMinus80DbAt96k() {
        for (f in doubleArrayOf(1_000.0, 5_000.0, 10_000.0, 15_000.0)) {
            val db = errorDb(f, 96_000)
            assertTrue("96 kHz, $f Hz: error $db dB", db < -80.0)
        }
    }

    @Test
    fun downsamplingKeepsTheBandBelowItsCutoffClean() {
        // 44.1 -> 32 kHz: the output Nyquist is 16 kHz, so only tones well below it are tested.
        for (f in doubleArrayOf(1_000.0, 5_000.0, 10_000.0)) {
            val db = errorDb(f, 32_000)
            assertTrue("32 kHz, $f Hz: error $db dB", db < -80.0)
        }
    }

    @Test
    fun resultDoesNotDependOnWhereTheBufferBoundariesFall() {
        // Same 4096 output frames cut into 1024-frame buffers and into 333-frame buffers must match sample for sample.
        val ratio = srcRate / 48_000
        val rs = StemResampler(ratio)
        val x = tone(7_000.0, 44_100 * 4)
        fun render(buf: Int, total: Int): FloatArray {
            val out = FloatArray(total * 2)
            val dst = FloatArray(buf * 2)
            var cur = 50_000L
            var done = 0
            while (done < total) {
                val n = minOf(buf, total - done)
                val exact = cur * ratio
                val base = floor(exact).toLong()
                val frac0 = exact - base
                val need = rs.framesNeeded(frac0, n)
                val start = (base - rs.padLeft).toInt()
                rs.process(x.copyOfRange(start * 2, (start + need) * 2), frac0, dst, n)
                System.arraycopy(dst, 0, out, done * 2, n * 2)
                cur += n
                done += n
            }
            return out
        }
        val a = render(1024, 4096)
        val b = render(333, 4096)
        var maxDiff = 0f
        for (i in a.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(a[i] - b[i]))
        assertTrue("max difference $maxDiff", maxDiff < 1e-6f)
    }

    @Test
    fun levelAtDcIsUnity() {
        val rs = StemResampler(srcRate / 48_000)
        val n = 256
        val frac0 = 0.37
        val src = FloatArray(rs.framesNeeded(frac0, n) * 2) { 0.5f }
        val dst = FloatArray(n * 2)
        rs.process(src, frac0, dst, n)
        for (i in 0 until n) assertEquals(0.5f, dst[i * 2], 1e-5f)
    }

    @Test
    fun noResamplingIsNeededAtTheSameRate() {
        assertFalse(StemResampler.needsResampling(1.0))
        assertTrue(StemResampler.needsResampling(44_100.0 / 48_000))
    }

    @Test
    fun framesNeededCoversEveryTapRead() {
        for (ratio in doubleArrayOf(44_100.0 / 48_000, 44_100.0 / 96_000, 44_100.0 / 32_000)) {
            val rs = StemResampler(ratio)
            for (frac0 in doubleArrayOf(0.0, 0.5, 0.999)) {
                val n = 1000
                val need = rs.framesNeeded(frac0, n)
                val lastPos = frac0 + (n - 1) * ratio
                val lastTapIndex = floor(lastPos).toInt() + StemResampler.TAPS - 1
                assertTrue("ratio $ratio frac $frac0", lastTapIndex < need)
            }
        }
    }
}
