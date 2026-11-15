package com.beatraxus.app.engine

import com.beatraxus.app.model.sanitizeUpscalerAmount
import com.beatraxus.app.model.sanitizeUpscalerCutoff
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Audio upscaler (Studio DSP > Master): gives a low-bitrate file back some of the air above the point where its
 * encoder cut the spectrum.
 *
 * It is a harmonic exciter, not a "magic" restoration, and it only adds content, it never removes any:
 *  1. a band just below the cutoff is picked out (about one octave, never above a quarter of the sample rate, so the
 *     harmonics stay below Nyquist and nothing aliases),
 *  2. a full-wave rectifier doubles its frequencies (every partial f becomes 2f, so the new content lines up with the
 *     music), and its level follows the band's own level,
 *  3. a 4th-order high-pass at the cutoff keeps only the new highs and removes the DC of the rectifier,
 *  4. that is mixed in at up to about 6 dB under the band it came from (amount 100%).
 * Silence stays silent. Stereo (2 channels) float audio at 32 kHz or more; anything else is left alone.
 *
 * Pure Kotlin, allocation-free in [process].
 */
internal class AudioUpscaler(sampleRate: Int) {

    private val sr: Double = sampleRate.coerceAtLeast(8_000).toDouble()

    private val bandHigh = Biquad()
    private val bandLow = Biquad()
    private val outHighA = Biquad()
    private val outHighB = Biquad()

    private var targetAmount = 0f
    private var currentAmount = 0f
    private val smoothing: Float = (1.0 - exp(-1.0 / (0.02 * sr))).toFloat()
    private var designedCutoff = -1f

    /** False once the effect is switched off and its fade-out has finished: [process] can be skipped. */
    val active: Boolean get() = targetAmount > 0f || currentAmount > SNAP

    /** [amount] 0..1 (0 = off), [cutoffHz] where the file's own highs stop. Safe to call while audio is flowing. */
    fun configure(amount: Float, cutoffHz: Float) {
        targetAmount = sanitizeUpscalerAmount(amount)
        val cutoff = sanitizeUpscalerCutoff(cutoffHz)
        if (cutoff != designedCutoff) {
            designedCutoff = cutoff
            val top = min(cutoff * 0.9, sr * 0.25 * 0.98)
            val bottom = top * 0.5
            bandHigh.highPass(bottom, sr)
            bandLow.lowPass(top, sr)
            val keep = min(cutoff.toDouble(), sr * 0.47)
            outHighA.highPass(keep, sr)
            outHighB.highPass(keep, sr)
        }
    }

    fun reset() {
        bandHigh.reset(); bandLow.reset(); outHighA.reset(); outHighB.reset()
    }

    /** Adds the new highs to [frames] interleaved stereo frames of [data], in place. */
    fun process(data: FloatArray, frames: Int) {
        if (frames <= 0 || designedCutoff < 0f) return
        for (f in 0 until frames) {
            currentAmount += smoothing * (targetAmount - currentAmount)
            if (targetAmount == 0f && currentAmount < SNAP) currentAmount = 0f
            val gain = currentAmount * AMOUNT_SCALE
            val i = f * 2
            for (c in 0..1) {
                val x = data[i + c].toDouble()
                val band = bandLow.process(c, bandHigh.process(c, x))
                val highs = outHighB.process(c, outHighA.process(c, abs(band)))
                if (gain > 0f) {
                    val y = x + gain * highs
                    data[i + c] = if (y > 1.0) 1f else if (y < -1.0) -1f else y.toFloat()
                }
            }
        }
    }

    /** RBJ Butterworth section (Q = 1/sqrt2) with separate state for the two channels. */
    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private val x1 = DoubleArray(2)
        private val x2 = DoubleArray(2)
        private val y1 = DoubleArray(2)
        private val y2 = DoubleArray(2)

        fun lowPass(fc: Double, sr: Double) {
            val w0 = 2.0 * PI * fc / sr
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            b0 = (1.0 - cosW) / 2.0 / a0
            b1 = (1.0 - cosW) / a0
            b2 = b0
            a1 = -2.0 * cosW / a0
            a2 = (1.0 - alpha) / a0
        }

        fun highPass(fc: Double, sr: Double) {
            val w0 = 2.0 * PI * fc / sr
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            b0 = (1.0 + cosW) / 2.0 / a0
            b1 = -(1.0 + cosW) / a0
            b2 = b0
            a1 = -2.0 * cosW / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(ch: Int, x: Double): Double {
            val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
            x2[ch] = x1[ch]; x1[ch] = x
            y2[ch] = y1[ch]; y1[ch] = y
            return y
        }

        fun reset() {
            x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        }

        private companion object {
            const val Q = 0.70710678
        }
    }

    private companion object {
        const val SNAP = 1e-4f

        /** The 2f part of a rectified sine is ~0.42 of its amplitude; x1.2 puts the new highs about 6 dB under the source band at amount 1. */
        const val AMOUNT_SCALE = 1.2f
    }
}
