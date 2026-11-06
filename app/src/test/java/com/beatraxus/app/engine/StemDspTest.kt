package com.beatraxus.app.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StemDspTest {
    @Test
    fun sanitizeZeroesNonFiniteAndClamps() {
        val a = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 9f, -9f, 0.5f)
        val bad = StemDsp.sanitize(a, 0, a.size)
        assertEquals(2, bad)
        assertEquals(0f, a[0], 0f)
        assertEquals(0f, a[1], 0f)
        assertEquals(StemDsp.MAX_ABS, a[2], 0f)
        assertEquals(-StemDsp.MAX_ABS, a[3], 0f)
        assertEquals(0.5f, a[4], 0f)
    }

    @Test
    fun peakTreatsNaNAsLoud() {
        assertTrue(StemDsp.peak(floatArrayOf(0f, Float.NaN), 2) > 1f)
        assertEquals(0.25f, StemDsp.peak(floatArrayOf(0.25f, -0.1f), 2), 0f)
    }

    @Test
    fun stftIstftRoundTripRestoresSignal() {
        val fft = Radix2FFT(StemDsp.N_FFT)
        val len = StemDsp.SEGMENT_SAMPLES
        val l = FloatArray(len) { (0.5 * sin(2 * PI * 440.0 * it / 44100)).toFloat() }
        val r = FloatArray(len) { (0.4 * sin(2 * PI * 1000.0 * it / 44100)).toFloat() }
        val spec = ByteBuffer.allocateDirect(4 * StemDsp.BINS * StemDsp.N_FRAMES * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val re = FloatArray(StemDsp.N_FFT)
        val im = FloatArray(StemDsp.N_FFT)
        StemDsp.forward(fft, l, r, len, spec, re, im)
        // Model layout has 4 sources x 4 channels; a source with the input spectrum in slot 0 is enough.
        val outL = FloatArray(StemDsp.PADDED_SAMPLES)
        val outR = FloatArray(StemDsp.PADDED_SAMPLES)
        val bad = StemDsp.inverseSource(fft, spec, 0, outL, outR, re, im)
        assertEquals(0, bad)
        var maxErr = 0f
        for (i in 1000 until len - 1000) {
            maxErr = maxOf(maxErr, abs(outL[i + StemDsp.PAD_SAMPLES] - l[i]), abs(outR[i + StemDsp.PAD_SAMPLES] - r[i]))
        }
        assertTrue("round trip error $maxErr", maxErr < 1e-3f)
    }

    @Test
    fun poisonedBinsAreCountedAndOutputStaysFinite() {
        val fft = Radix2FFT(StemDsp.N_FFT)
        val spec = ByteBuffer.allocateDirect(4 * StemDsp.BINS * StemDsp.N_FRAMES * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        spec.put(5 * StemDsp.N_FRAMES + 7, Float.NaN)
        val outL = FloatArray(StemDsp.PADDED_SAMPLES)
        val outR = FloatArray(StemDsp.PADDED_SAMPLES)
        val bad = StemDsp.inverseSource(fft, spec, 0, outL, outR, FloatArray(StemDsp.N_FFT), FloatArray(StemDsp.N_FFT))
        assertEquals(1, bad)
        assertTrue(outL.all { it - it == 0f } && outR.all { it - it == 0f })
    }
}
