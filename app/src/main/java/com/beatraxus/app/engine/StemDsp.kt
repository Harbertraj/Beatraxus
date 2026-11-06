package com.beatraxus.app.engine

import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 FFT implementation for size N=4096 with precomputed twiddle tables.
 */
class Radix2FFT(val n: Int = 4096) {
    private val logN = 31 - Integer.numberOfLeadingZeros(n)
    private val bitRev = IntArray(n) { i ->
        var rev = 0
        var temp = i
        for (j in 0 until logN) {
            rev = (rev shl 1) or (temp and 1)
            temp = temp ushr 1
        }
        rev
    }

    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = 2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    fun fft(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = false)
    }

    fun ifft(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = true)
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = bitRev[i]
            if (i < j) {
                val tempR = re[i]
                re[i] = re[j]
                re[j] = tempR
                val tempI = im[i]
                im[i] = im[j]
                im[j] = tempI
            }
        }

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            for (i in 0 until n step len) {
                for (j in 0 until halfLen) {
                    val tableIdx = j * step
                    val cosW = cosTable[tableIdx]
                    val sinW = if (inverse) sinTable[tableIdx] else -sinTable[tableIdx]

                    val reRight = re[i + j + halfLen]
                    val imRight = im[i + j + halfLen]

                    val tRe = reRight * cosW - imRight * sinW
                    val tIm = reRight * sinW + imRight * cosW

                    val uRe = re[i + j]
                    val uIm = im[i + j]

                    re[i + j] = uRe + tRe
                    im[i + j] = uIm + tIm
                    re[i + j + halfLen] = uRe - tRe
                    im[i + j + halfLen] = uIm - tIm
                }
            }
            len = len shl 1
        }

        if (inverse) {
            // Incorporate scale 1/64.0f (undoes 1/sqrt(4096) STFT scale)
            val scale = 1.0f / 64.0f
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }
}

/**
 * Pure signal maths of the stem separator (no Android, no ONNX), so it can be unit tested on the JVM.
 *
 * The model works on a spectrogram `[1, 4, 2049, 476]`: channel 0..3 = left real, left imaginary, right real, right
 * imaginary; bins are the second axis, time frames the last. Output is `[1, 16, 2049, 476]` = 4 sources x those 4.
 *
 * Both directions pack the left and right channel into ONE complex FFT (left in the real part, right in the
 * imaginary part). A real signal has a conjugate-symmetric spectrum, so the two spectra can be separated again
 * afterwards. That halves the number of 4096-point FFTs per chunk (952 -> 476 forward, 3808 -> 1904 inverse),
 * which is the biggest cost outside the model itself.
 */
internal object StemDsp {
    const val N_FFT = 4096
    const val HOP_SIZE = 1024
    const val BINS = N_FFT / 2 + 1
    const val N_FRAMES = 476
    const val PAD_SAMPLES = 2048
    const val SEGMENT_SAMPLES = 485100
    const val PADDED_SAMPLES = 490496
    const val SOURCES = 4

    /** Stem samples are clamped to this (about +12 dB over full scale): anything beyond is a broken model output. */
    const val MAX_ABS = 4f

    private const val SCALE = 1.0f / 64.0f // 1 / sqrt(N_FFT), the model's orthonormal STFT scale

    /** 1 / (number of STFT frames covering each sample); the STFT window is rectangular, so overlap-add is a plain average. */
    val overlapInverse: FloatArray by lazy {
        val count = IntArray(PADDED_SAMPLES)
        for (t in 0 until N_FRAMES) {
            val offset = t * HOP_SIZE
            val end = minOf(PADDED_SAMPLES, offset + N_FFT)
            for (p in offset until end) count[p]++
        }
        FloatArray(PADDED_SAMPLES) { if (count[it] > 0) 1.0f / count[it] else 1.0f }
    }

    /** Mirrored sample lookup used as the STFT's centre padding. */
    fun reflect(idx: Int, n: Int, pcm: FloatArray): Float {
        if (n == 0) return 0f
        var p = idx
        if (p < 0) p = -p
        if (p >= n) p = 2 * n - 2 - p
        p = p.coerceIn(0, n - 1)
        return pcm[p]
    }

    /** Largest absolute value of the first [n] samples (NaN counts as loud so it is never skipped as silence). */
    fun peak(a: FloatArray, n: Int): Float {
        var m = 0f
        for (i in 0 until minOf(n, a.size)) {
            val v = a[i]
            if (v - v != 0f) return Float.MAX_VALUE
            val x = if (v < 0f) -v else v
            if (x > m) m = x
        }
        return m
    }

    /** Replaces NaN / Infinity with 0 and clamps to +-[limit]. Returns how many samples were not finite. */
    fun sanitize(buf: FloatArray, from: Int, to: Int, limit: Float = MAX_ABS): Int {
        var bad = 0
        for (i in from until to) {
            val v = buf[i]
            if (v - v != 0f) {
                buf[i] = 0f
                bad++
            } else if (v > limit) {
                buf[i] = limit
            } else if (v < -limit) {
                buf[i] = -limit
            }
        }
        return bad
    }

    /**
     * Forward STFT of one stereo chunk into [dst] (the model input layout, absolute indexes).
     * [len] is the number of valid samples in [left] / [right]; [re] / [im] are scratch arrays of [N_FFT].
     */
    fun forward(fft: Radix2FFT, left: FloatArray, right: FloatArray, len: Int, dst: FloatBuffer, re: FloatArray, im: FloatArray) {
        val plane = BINS * N_FRAMES
        for (t in 0 until N_FRAMES) {
            val start = t * HOP_SIZE - PAD_SAMPLES
            for (n in 0 until N_FFT) {
                re[n] = reflect(start + n, len, left)
                im[n] = reflect(start + n, len, right)
            }
            fft.fft(re, im)
            for (f in 0 until BINS) {
                val g = (N_FFT - f) and (N_FFT - 1)
                val aRe = re[f]
                val aIm = im[f]
                val bRe = re[g]
                val bIm = im[g]
                val i0 = f * N_FRAMES + t
                // left = (Z[k] + conj Z[N-k]) / 2, right = (Z[k] - conj Z[N-k]) / 2i
                dst.put(i0, (aRe + bRe) * 0.5f * SCALE)
                dst.put(i0 + plane, (aIm - bIm) * 0.5f * SCALE)
                dst.put(i0 + 2 * plane, (aIm + bIm) * 0.5f * SCALE)
                dst.put(i0 + 3 * plane, (bRe - aRe) * 0.5f * SCALE)
            }
        }
    }

    /**
     * Inverse STFT of one model source ([source] 0..3 inside [spec], 4 channels each) into [outL] / [outR]
     * (both [PADDED_SAMPLES] long, still including the centre padding). Non-finite bins are zeroed, never passed on.
     * Returns how many bins were non-finite, so the caller can tell a broken model run from a good one.
     */
    fun inverseSource(
        fft: Radix2FFT,
        spec: FloatBuffer,
        source: Int,
        outL: FloatArray,
        outR: FloatArray,
        re: FloatArray,
        im: FloatArray
    ): Int {
        java.util.Arrays.fill(outL, 0, PADDED_SAMPLES, 0f)
        java.util.Arrays.fill(outR, 0, PADDED_SAMPLES, 0f)
        val plane = BINS * N_FRAMES
        val last = BINS - 1
        var bad = 0
        for (t in 0 until N_FRAMES) {
            for (f in 0 until BINS) {
                val i0 = (source * 4 * BINS + f) * N_FRAMES + t
                var lRe = spec.get(i0)
                var lIm = spec.get(i0 + plane)
                var rRe = spec.get(i0 + 2 * plane)
                var rIm = spec.get(i0 + 3 * plane)
                if ((lRe - lRe) + (lIm - lIm) + (rRe - rRe) + (rIm - rIm) != 0f) {
                    if (lRe - lRe != 0f) lRe = 0f
                    if (lIm - lIm != 0f) lIm = 0f
                    if (rRe - rRe != 0f) rRe = 0f
                    if (rIm - rIm != 0f) rIm = 0f
                    bad++
                }
                if (f == 0 || f == last) {
                    // DC and Nyquist of a real signal are real
                    lIm = 0f
                    rIm = 0f
                }
                // Z = L + iR on the full spectrum, built from the two half spectra
                re[f] = lRe - rIm
                im[f] = lIm + rRe
                if (f != 0 && f != last) {
                    re[N_FFT - f] = lRe + rIm
                    im[N_FFT - f] = rRe - lIm
                }
            }
            fft.ifft(re, im) // real part = left, imaginary part = right
            val offset = t * HOP_SIZE
            val n = minOf(N_FFT, PADDED_SAMPLES - offset)
            for (k in 0 until n) {
                outL[offset + k] += re[k]
                outR[offset + k] += im[k]
            }
        }
        val inv = overlapInverse
        for (i in 0 until PADDED_SAMPLES) {
            outL[i] *= inv[i]
            outR[i] *= inv[i]
        }
        return bad
    }
}
