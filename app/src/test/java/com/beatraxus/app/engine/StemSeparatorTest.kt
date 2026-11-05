package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class StemSeparatorTest {

    @Test
    fun overlapAddOutputLengthEqualsInputLength() {
        val numFrames = 1_000_000
        val inputPcm = FloatArray(numFrames * 2) { 0.5f }
        val chunkSize = 485100
        val hopSize = 121275

        val result = StemSeparator.processOverlapAdd(
            inputPcm = inputPcm,
            chunkSizeFrames = chunkSize,
            hopSizeFrames = hopSize
        ) { inChunk, _ ->
            Array(4) { Array(2) { ch -> inChunk[ch].copyOf() } }
        }

        assertNotNull(result)
        assertEquals(4, result!!.size)

        for (s in 0 until 4) {
            assertEquals(inputPcm.size, result[s].size)
        }
    }

    @Test
    fun overlapAddConstantSignalStaysConstant() {
        val numFrames = 970200
        val constantValue = 0.75f
        val inputPcm = FloatArray(numFrames * 2) { constantValue }
        val chunkSize = 485100
        val hopSize = 121275

        val result = StemSeparator.processOverlapAdd(
            inputPcm = inputPcm,
            chunkSizeFrames = chunkSize,
            hopSizeFrames = hopSize
        ) { inChunk, _ ->
            Array(4) { Array(2) { ch -> inChunk[ch].copyOf() } }
        }

        assertNotNull(result)

        val stem0 = result!![0]
        assertEquals(inputPcm.size, stem0.size)

        for (i in 0 until stem0.size) {
            assertEquals(constantValue, stem0[i], 1e-3f)
        }
    }

    @Test
    fun stftIstftRoundTrip() {
        val nFft = 4096
        val hopSize = 1024
        val nFrames = 476
        val segmentSamples = 485100
        val padSamples = 2048
        val paddedSamples = 490496

        val radix2Fft = Radix2FFT(nFft)

        val inputPcm = FloatArray(segmentSamples) { i ->
            (sin(2.0 * PI * 440.0 * i / 44100.0) * 0.5 +
             cos(2.0 * PI * 1000.0 * i / 44100.0) * 0.3).toFloat()
        }

        val reSpecs = Array(nFrames) { FloatArray(nFft / 2 + 1) }
        val imSpecs = Array(nFrames) { FloatArray(nFft / 2 + 1) }

        val reBuf = FloatArray(nFft)
        val imBuf = FloatArray(nFft)
        val scale = 1.0f / 64.0f

        fun getReflectSample(idx: Int, n: Int, pcm: FloatArray): Float {
            var p = idx
            if (p < 0) p = -p
            if (p >= n) p = 2 * n - 2 - p
            p = p.coerceIn(0, n - 1)
            return pcm[p]
        }

        for (t in 0 until nFrames) {
            val frameStart = t * hopSize - padSamples
            for (n in 0 until nFft) {
                val sampleIdx = frameStart + n
                reBuf[n] = getReflectSample(sampleIdx, segmentSamples, inputPcm)
                imBuf[n] = 0.0f
            }
            radix2Fft.fft(reBuf, imBuf)
            for (f in 0..2048) {
                reSpecs[t][f] = reBuf[f] * scale
                imSpecs[t][f] = imBuf[f] * scale
            }
        }

        val outAccum = FloatArray(paddedSamples)
        val winSqAccum = FloatArray(paddedSamples)

        for (t in 0 until nFrames) {
            for (f in 0..2048) {
                reBuf[f] = reSpecs[t][f]
                imBuf[f] = imSpecs[t][f]
            }
            for (f in 1 until 2048) {
                reBuf[nFft - f] = reBuf[f]
                imBuf[nFft - f] = -imBuf[f]
            }
            imBuf[0] = 0f
            imBuf[2048] = 0f

            radix2Fft.ifft(reBuf, imBuf)

            val offset = t * hopSize
            for (n in 0 until nFft) {
                if (offset + n < paddedSamples) {
                    outAccum[offset + n] += reBuf[n]
                    winSqAccum[offset + n] += 1.0f
                }
            }
        }

        for (i in 0 until paddedSamples) {
            val w = winSqAccum[i]
            if (w > 1e-6f) outAccum[i] /= w
        }

        var maxError = 0f
        for (i in 0 until segmentSamples) {
            val err = abs(inputPcm[i] - outAccum[padSamples + i])
            if (err > maxError) maxError = err
        }

        assertTrue("Max STFT->iSTFT reconstruction error $maxError exceeds 1e-3", maxError < 1e-3f)
    }
}
