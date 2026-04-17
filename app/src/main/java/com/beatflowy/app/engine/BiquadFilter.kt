package com.beatflowy.app.engine

import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════
 * BiquadFilter.kt
 *
 * A second-order IIR (biquad) digital filter implementing the Audio EQ Cookbook
 * by Robert Bristow-Johnson.
 *
 * Transfer function (Z-domain):
 *
 *         b0 + b1·z⁻¹ + b2·z⁻²
 *  H(z) = ────────────────────────
 *          1 + a1·z⁻¹ + a2·z⁻²
 *
 * Difference equation (Direct Form I):
 *   y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2]
 *          - a1·y[n-1] - a2·y[n-2]
 *
 * Filter types:
 *   - PEAKING_EQ   → boost / cut at centre frequency (used for mid EQ bands)
 *   - LOW_SHELF    → shelving below frequency (31 Hz band)
 *   - HIGH_SHELF   → shelving above frequency (16 kHz band)
 *   - LOW_PASS     → utility
 *   - HIGH_PASS    → utility
 *
 * All coefficient calculations follow the Audio EQ Cookbook conventions.
 * ════════════════════════════════════════════════════════════════════════════
 */
class BiquadFilter {

    // ── Filter coefficients (normalised: a0 = 1) ────────────────────────────
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // ── Delay-line state (one per stereo channel) ────────────────────────────
    // Left channel
    private var x1L = 0.0; private var x2L = 0.0
    private var y1L = 0.0; private var y2L = 0.0
    // Right channel
    private var x1R = 0.0; private var x2R = 0.0
    private var y1R = 0.0; private var y2R = 0.0

    // ── Filter type enum ─────────────────────────────────────────────────────
    enum class Type {
        PEAKING_EQ,
        LOW_SHELF,
        HIGH_SHELF,
        LOW_PASS,
        HIGH_PASS,
        BAND_PASS,
        NOTCH
    }

    // ════════════════════════════════════════════════════════════════════════
    // Coefficient calculation
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Recalculate filter coefficients.
     *
     * @param type        Filter type
     * @param freqHz      Centre / corner frequency in Hz
     * @param gainDb      Boost (+) or cut (−) in dB  [PEAKING / SHELF only]
     * @param qFactor     Q / bandwidth factor (0.1 – 10.0)
     * @param sampleRate  Audio sample rate in Hz
     */
    fun setCoefficients(
        type: Type,
        freqHz: Double,
        gainDb: Double,
        qFactor: Double,
        sampleRate: Int
    ) {
        // Pre-warp digital frequency to analog radians
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha  = sinW0 / (2.0 * qFactor)   // bandwidth form

        // Linear amplitude gain for peaking / shelf formulas
        val A = 10.0.pow(gainDb / 40.0)         // sqrt(10^(dB/20))

        val (nb0, nb1, nb2, na0, na1, na2) = when (type) {

            // ── Peaking EQ ───────────────────────────────────────────────────
            // Used for all mid-range bands (62 Hz – 8 kHz)
            Type.PEAKING_EQ -> {
                val alphaTimesA  = alpha * A
                val alphaDivA    = alpha / A
                Coeffs(
                    nb0 =  1.0 + alphaTimesA,
                    nb1 = -2.0 * cosW0,
                    nb2 =  1.0 - alphaTimesA,
                    na0 =  1.0 + alphaDivA,
                    na1 = -2.0 * cosW0,
                    na2 =  1.0 - alphaDivA
                )
            }

            // ── Low-shelf ────────────────────────────────────────────────────
            // Used for the 31 Hz (lowest) band
            Type.LOW_SHELF -> {
                val sqrtA = sqrt(A)
                val twoSqrtAlpha = 2.0 * sqrtA * alpha
                Coeffs(
                    nb0 =       A * ((A + 1) - (A - 1) * cosW0 + twoSqrtAlpha),
                    nb1 = 2.0 * A * ((A - 1) - (A + 1) * cosW0),
                    nb2 =       A * ((A + 1) - (A - 1) * cosW0 - twoSqrtAlpha),
                    na0 =           (A + 1) + (A - 1) * cosW0 + twoSqrtAlpha,
                    na1 =    -2.0 * ((A - 1) + (A + 1) * cosW0),
                    na2 =           (A + 1) + (A - 1) * cosW0 - twoSqrtAlpha
                )
            }

            // ── High-shelf ───────────────────────────────────────────────────
            // Used for the 16 kHz (highest) band
            Type.HIGH_SHELF -> {
                val sqrtA = sqrt(A)
                val twoSqrtAlpha = 2.0 * sqrtA * alpha
                Coeffs(
                    nb0 =       A * ((A + 1) + (A - 1) * cosW0 + twoSqrtAlpha),
                    nb1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0),
                    nb2 =       A * ((A + 1) + (A - 1) * cosW0 - twoSqrtAlpha),
                    na0 =           (A + 1) - (A - 1) * cosW0 + twoSqrtAlpha,
                    na1 =  2.0 *   ((A - 1) - (A + 1) * cosW0),
                    na2 =           (A + 1) - (A - 1) * cosW0 - twoSqrtAlpha
                )
            }

            // ── Low-pass ─────────────────────────────────────────────────────
            Type.LOW_PASS -> Coeffs(
                nb0 = (1.0 - cosW0) / 2.0,
                nb1 =  1.0 - cosW0,
                nb2 = (1.0 - cosW0) / 2.0,
                na0 =  1.0 + alpha,
                na1 = -2.0 * cosW0,
                na2 =  1.0 - alpha
            )

            // ── High-pass ────────────────────────────────────────────────────
            Type.HIGH_PASS -> Coeffs(
                nb0 =  (1.0 + cosW0) / 2.0,
                nb1 = -(1.0 + cosW0),
                nb2 =  (1.0 + cosW0) / 2.0,
                na0 =   1.0 + alpha,
                na1 =  -2.0 * cosW0,
                na2 =   1.0 - alpha
            )

            // ── Band-pass ────────────────────────────────────────────────────
            Type.BAND_PASS -> Coeffs(
                nb0 =  sinW0 / 2.0,
                nb1 =  0.0,
                nb2 = -sinW0 / 2.0,
                na0 =  1.0 + alpha,
                na1 = -2.0 * cosW0,
                na2 =  1.0 - alpha
            )

            // ── Notch ────────────────────────────────────────────────────────
            Type.NOTCH -> Coeffs(
                nb0 =  1.0,
                nb1 = -2.0 * cosW0,
                nb2 =  1.0,
                na0 =  1.0 + alpha,
                na1 = -2.0 * cosW0,
                na2 =  1.0 - alpha
            )
        }

        // Normalise by a0
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sample processing
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Process a single LEFT-channel sample using Direct Form I.
     * Call [processRight] for the right channel to maintain separate state.
     *
     * @param xn  Input sample (float converted to double for precision)
     * @return    Filtered output sample
     */
    fun processLeft(xn: Float): Float {
        val yn = b0 * xn + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        // Update delay lines
        x2L = x1L;  x1L = xn.toDouble()
        y2L = y1L;  y1L = yn
        return yn.toFloat()
    }

    /**
     * Process a single RIGHT-channel sample (separate delay state).
     */
    fun processRight(xn: Float): Float {
        val yn = b0 * xn + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R;  x1R = xn.toDouble()
        y2R = y1R;  y1R = yn
        return yn.toFloat()
    }

    /**
     * Process a stereo interleaved FloatArray buffer in-place.
     * Buffer layout: [L0, R0, L1, R1, ...]
     *
     * @param buffer  Interleaved stereo PCM float samples
     * @param frames  Number of stereo frames (buffer.size / 2)
     */
    fun processStereoInterleaved(buffer: FloatArray, frames: Int) {
        var i = 0
        repeat(frames) {
            buffer[i]   = processLeft(buffer[i])
            buffer[i+1] = processRight(buffer[i+1])
            i += 2
        }
    }

    /**
     * Process a mono FloatArray buffer in-place (left channel state).
     */
    fun processMono(buffer: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            buffer[i] = processLeft(buffer[i])
        }
    }

    /** Reset delay-line state (call when seeking or switching tracks). */
    fun reset() {
        x1L = 0.0; x2L = 0.0; y1L = 0.0; y2L = 0.0
        x1R = 0.0; x2R = 0.0; y1R = 0.0; y2R = 0.0
    }

    // ── Coefficient container (destructurable) ───────────────────────────────
    private data class Coeffs(
        val nb0: Double, val nb1: Double, val nb2: Double,
        val na0: Double, val na1: Double, val na2: Double
    )
    private operator fun Coeffs.component1() = nb0
    private operator fun Coeffs.component2() = nb1
    private operator fun Coeffs.component3() = nb2
    private operator fun Coeffs.component4() = na0
    private operator fun Coeffs.component5() = na1
    private operator fun Coeffs.component6() = na2
}
