package com.beatflowy.app.engine

import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════
 * Resampler.kt
 *
 * Intelligent DSP upsampler implementing the Beatflowy resampling policy:
 *
 *   44.1 kHz  →  48 kHz      (factor ≈ 1.0884)
 *   48 kHz    →  96 kHz      (factor = 2.0, integer ratio — clean)
 *   96 kHz+   →  pass-through (no processing)
 *   OFF        →  pass-through regardless
 *
 * Algorithm — Linear-phase polyphase FIR resampling:
 *
 *   1. Compute rational L/M ratio from input/output sample rates.
 *   2. Upsample by L (insert L−1 zeros between each sample).
 *   3. Apply low-pass FIR anti-aliasing filter at min(Fin, Fout)/2.
 *   4. Downsample by M (keep 1 of every M samples).
 *
 *   For the 48→96 case, L=2, M=1 — this simplifies to a half-band FIR
 *   interpolation filter which is highly efficient.
 *
 *   For the 44.1→48 case, L=160, M=147 (exact rational approximation).
 *   This is computed using the polyphase decomposition so that the filter
 *   runs at the input rate (not the 160× upsampled rate), making it practical.
 *
 * FIR filter design:
 *   - Kaiser window, β = 8.0 (≈ −80 dB stopband attenuation)
 *   - Filter length: min(L×64, 511) taps
 *   - Cutoff: 0.45 × min(Fin, Fout) / Fin  (5% guard band)
 *
 * Memory:
 *   - One circular history buffer per stereo channel (length = FIR half-length)
 *   - Polyphase sub-filter bank pre-computed at configuration time
 * ════════════════════════════════════════════════════════════════════════════
 */
class Resampler {

    // ── Config ───────────────────────────────────────────────────────────────
    @Volatile var isEnabled: Boolean = true

    private var inputSampleRate  = 44_100
    private var outputSampleRate = 48_000
    private var upFactor   = 1           // L
    private var downFactor = 1           // M

    // ── Polyphase filter bank ─────────────────────────────────────────────────
    // phases[p] = sub-filter coefficients for polyphase phase p (length = tapsPerPhase)
    private var phases: Array<FloatArray> = emptyArray()
    private var tapsPerPhase = 0

    // ── Delay history (circular buffer for each channel) ─────────────────────
    private var historyL: FloatArray = FloatArray(0)
    private var historyR: FloatArray = FloatArray(0)
    private var historyPos = 0          // write pointer into history ring

    // Input sample accumulator (for 44.1→48 rational resampling)
    private var inputPhase = 0          // tracks which polyphase to use next

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Configure the resampler for a given input → output rate pair.
     * Must be called before the first [process] call, and whenever the rates change.
     *
     * @param inHz   Input sample rate  (e.g. 44100, 48000, 96000)
     * @param outHz  Target output rate (e.g. 48000, 96000)
     */
    fun configure(inHz: Int, outHz: Int) {
        inputSampleRate  = inHz
        outputSampleRate = outHz

        if (!isEnabled || inHz == outHz || outHz < inHz) {
            // Pass-through: identity filter
            upFactor = 1; downFactor = 1
            phases = emptyArray()
            return
        }

        // Compute minimal integer L/M pair
        val (L, M) = rationalRatio(inHz, outHz)
        upFactor   = L
        downFactor = M

        // Design polyphase FIR bank
        buildPolyphaseBank(L, M, inHz, outHz)

        // Allocate history buffer (length = tapsPerPhase, initialised to silence)
        historyL = FloatArray(tapsPerPhase)
        historyR = FloatArray(tapsPerPhase)
        historyPos = 0
        inputPhase = 0
    }

    /** Reset history (call on seek / track change). */
    fun reset() {
        historyL.fill(0f)
        historyR.fill(0f)
        historyPos = 0
        inputPhase = 0
    }

    /**
     * Resample a stereo interleaved input buffer into output.
     *
     * @param input       Interleaved stereo PCM: [L0,R0, L1,R1, …]
     * @param inputFrames Number of stereo frames in input
     * @return            New interleaved stereo buffer at target sample rate.
     *                    Returns the same [input] reference if pass-through.
     */
    fun process(input: FloatArray, inputFrames: Int): Pair<FloatArray, Int> {
        if (!isEnabled || phases.isEmpty() || (upFactor == 1 && downFactor == 1)) {
            return Pair(input, inputFrames)
        }

        // Worst-case output frame count (add margin for rational fractions)
        val maxOutputFrames = (inputFrames.toLong() * upFactor / downFactor + 2).toInt()
        val outputL = FloatArray(maxOutputFrames)
        val outputR = FloatArray(maxOutputFrames)
        var outIdx  = 0

        for (f in 0 until inputFrames) {
            val inL = input[f * 2]
            val inR = input[f * 2 + 1]

            // Write new sample into circular history
            historyL[historyPos] = inL
            historyR[historyPos] = inR

            // Compute all polyphase outputs that fall within this input step
            while (inputPhase < upFactor) {
                if (inputPhase % downFactor == 0) {
                    if (outIdx < maxOutputFrames) {
                        outputL[outIdx] = applyPhase(inputPhase, historyL)
                        outputR[outIdx] = applyPhase(inputPhase, historyR)
                        outIdx++
                    }
                }
                inputPhase++
            }
            inputPhase -= upFactor      // advance by one input sample

            historyPos = (historyPos + 1) % tapsPerPhase
        }

        // Interleave L+R into output
        val outputFrames = outIdx
        val output = FloatArray(outputFrames * 2)
        for (i in 0 until outputFrames) {
            output[i * 2]     = outputL[i]
            output[i * 2 + 1] = outputR[i]
        }
        return Pair(output, outputFrames)
    }

    // ── Private: polyphase filter application ────────────────────────────────

    /**
     * Apply polyphase sub-filter [phase] to the circular history buffer.
     * This replaces computing a full convolution at the upsampled rate.
     */
    private fun applyPhase(phase: Int, history: FloatArray): Float {
        val coeffs = phases[phase]
        var acc = 0.0
        val n = tapsPerPhase
        for (k in 0 until n) {
            // Walk backwards through history (newest sample at historyPos-1)
            val histIdx = (historyPos - 1 - k + n * 2) % n
            acc += coeffs[k].toDouble() * history[histIdx].toDouble()
        }
        return acc.toFloat()
    }

    // ── Private: FIR design ──────────────────────────────────────────────────

    /**
     * Build polyphase filter bank for L/M resampling.
     *
     * Steps:
     *  1. Design a low-pass FIR prototype (Kaiser window) at cutoff = 0.45/max(L,M)
     *  2. Decompose into L polyphase sub-filters
     */
    private fun buildPolyphaseBank(L: Int, M: Int, inHz: Int, outHz: Int) {
        // Number of FIR taps: balance between quality and CPU
        val totalTaps = (L * 32).coerceIn(64, 511).let {
            // Round up so it's divisible by L
            if (it % L == 0) it else it + (L - it % L)
        }
        tapsPerPhase = totalTaps / L

        // Normalised cutoff frequency (relative to upsampled rate)
        val cutoff = 0.45f / max(L, M).toFloat()

        // Design full FIR prototype
        val prototype = designKaiserFir(totalTaps, cutoff.toDouble(), beta = 8.0)

        // Scale by L to compensate for upsampling gain
        val scale = L.toFloat()
        for (i in prototype.indices) prototype[i] = (prototype[i] * scale).toFloat()

        // Decompose into L polyphase sub-filters
        // phases[p][k] = prototype[p + k*L]
        phases = Array(L) { p ->
            FloatArray(tapsPerPhase) { k ->
                val idx = p + k * L
                if (idx < totalTaps) prototype[idx] else 0f
            }
        }
    }

    /**
     * Kaiser-windowed sinc FIR filter design.
     *
     * @param numTaps  Filter length (odd preferred for linear phase)
     * @param cutoff   Normalised cutoff [0, 0.5]  (0.5 = Nyquist)
     * @param beta     Kaiser window shape parameter (8.0 ≈ −80 dB stopband)
     * @return         FloatArray of FIR coefficients
     */
    private fun designKaiserFir(numTaps: Int, cutoff: Double, beta: Double): FloatArray {
        val coeffs = FloatArray(numTaps)
        val M      = numTaps - 1
        val i0Beta = besselI0(beta)

        for (n in 0..M) {
            val sincArg = n - M / 2.0
            val sinc = if (sincArg == 0.0) {
                2.0 * cutoff
            } else {
                sin(2.0 * PI * cutoff * sincArg) / (PI * sincArg)
            }

            // Kaiser window weight at tap n
            val windowArg = 2.0 * n / M - 1.0          // maps [0,M] → [-1,+1]
            val kaiser = besselI0(beta * sqrt(max(0.0, 1.0 - windowArg * windowArg))) / i0Beta

            coeffs[n] = (sinc * kaiser).toFloat()
        }
        return coeffs
    }

    /**
     * Modified zeroth-order Bessel function I₀(x) — needed for Kaiser window.
     * Computed via power series (converges in ~15 terms for typical β).
     */
    private fun besselI0(x: Double): Double {
        var sum  = 1.0
        var term = 1.0
        val x2   = x * x / 4.0
        for (k in 1..20) {
            term *= x2 / (k * k)
            sum  += term
            if (term < 1e-12 * sum) break
        }
        return sum
    }

    // ── Private: rational ratio ───────────────────────────────────────────────

    /**
     * Convert two sample rates to a minimal integer L/M ratio.
     * e.g. 44100 → 48000 gives gcd=300, so L=160, M=147
     *      48000 → 96000 gives gcd=48000, so L=2, M=1
     */
    private fun rationalRatio(inHz: Int, outHz: Int): Pair<Int, Int> {
        val g = gcd(inHz, outHz)
        return Pair(outHz / g, inHz / g)
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    // ── Companion: policy ─────────────────────────────────────────────────────
    companion object {
        /**
         * Beatflowy resampling policy.
         * Returns the target output sample rate for a given input.
         *
         * @param inputHz     Detected input sample rate
         * @param enabled     Whether the resampling engine is ON
         */
        fun targetSampleRate(inputHz: Int, enabled: Boolean): Int {
            if (!enabled) return inputHz
            return when {
                inputHz <= 44_100 -> 48_000
                inputHz <= 48_000 -> 96_000
                else              -> inputHz   // 96 kHz+ — no further upsampling
            }
        }
    }
}
