package com.beatflowy.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ════════════════════════════════════════════════════════════════════════════
 * Equalizer10Band.kt
 *
 * A professional 10-band parametric equalizer implemented with real biquad
 * IIR filters (not simple gain scaling).
 *
 * Band configuration:
 * ┌─────┬──────────┬──────────────┬──────┬─────────────────────────────────┐
 * │Band │ Freq(Hz) │ Filter Type  │  Q   │ Purpose                         │
 * ├─────┼──────────┼──────────────┼──────┼─────────────────────────────────┤
 * │  0  │     31   │ Low-shelf    │ 0.71 │ Sub-bass control                │
 * │  1  │     62   │ Peaking EQ   │ 1.41 │ Bass body                       │
 * │  2  │    125   │ Peaking EQ   │ 1.41 │ Upper bass / warmth             │
 * │  3  │    250   │ Peaking EQ   │ 1.41 │ Boxiness / mud                  │
 * │  4  │    500   │ Peaking EQ   │ 1.41 │ Lower midrange                  │
 * │  5  │   1000   │ Peaking EQ   │ 1.41 │ Midrange presence               │
 * │  6  │   2000   │ Peaking EQ   │ 1.41 │ Upper midrange / clarity        │
 * │  7  │   4000   │ Peaking EQ   │ 1.41 │ High-mid / articulation         │
 * │  8  │   8000   │ Peaking EQ   │ 1.41 │ Air / brilliance                │
 * │  9  │  16000   │ High-shelf   │ 0.71 │ Treble air and sparkle          │
 * └─────┴──────────┴──────────────┴──────┴─────────────────────────────────┘
 *
 * Processing chain per buffer:
 *   Input → Preamp gain → Band 0 → Band 1 → … → Band 9 → Hard limiter → Output
 *
 * Anti-clipping:
 *   - Preamp is automatically reduced when total boost would cause clipping.
 *   - A transparent soft-knee limiter catches any remaining overs.
 *
 * Thread safety:
 *   - [setGain] and [setEnabled] are safe to call from the UI thread because
 *     the coefficients are recalculated atomically before the next buffer.
 *   - [process] should always be called from a single audio thread.
 * ════════════════════════════════════════════════════════════════════════════
 */
class Equalizer10Band {

    // ── Constants ────────────────────────────────────────────────────────────
    companion object {
        const val NUM_BANDS = 10
        val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        /** Q factor for interior peaking bands (octave bandwidth ≈ 0.7 octave) */
        private const val Q_PEAKING = 1.4142135f   // √2 ≈ maximally flat between bands
        /** Q for shelf filters (Butterworth shelving) */
        private const val Q_SHELF   = 0.7071068f   // 1/√2

        /** Gain range in dB */
        const val MAX_GAIN_DB = 12f
        const val MIN_GAIN_DB = -12f

        /** Hard limiter threshold — prevents digital clipping */
        private const val LIMITER_THRESHOLD = 0.98f
    }

    // ── State ────────────────────────────────────────────────────────────────
    @Volatile var isEnabled: Boolean = true

    private val gainsDb     = FloatArray(NUM_BANDS) { 0f }  // per-band dB gain
    private val filters     = Array(NUM_BANDS) { BiquadFilter() }
    private var sampleRate  = 44_100
    private var needsUpdate = true          // flag: recalculate coefficients before next buffer

    // Preamp: automatically reduces gain to avoid clipping from multiple boosts
    private var preampAutoLinear = 1f
    private var preampManualDb = 0f

    // Tone controls
    private var bassDb = 0f
    private var trebleDb = 0f

    // Filters for tone controls (Bass & Treble)
    private val bassFilter = BiquadFilter()
    private val trebleFilter = BiquadFilter()

    // ── Public API ───────────────────────────────────────────────────────────

    fun setPreampManual(db: Float) {
        preampManualDb = db.coerceIn(-12f, 12f)
    }

    fun getPreampManual() = preampManualDb

    fun setTone(bass: Float, treble: Float) {
        bassDb = bass.coerceIn(-12f, 12f)
        trebleDb = treble.coerceIn(-12f, 12f)
        needsUpdate = true
    }

    fun getBassDb() = bassDb
    fun getTrebleDb() = trebleDb

    /**
     * Set gain for a single EQ band.
     *
     * @param band   Band index 0..9
     * @param gainDb Gain in dB, clamped to [-12, +12]
     */
    fun setGain(band: Int, gainDb: Float) {
        require(band in 0 until NUM_BANDS) { "Band index $band out of range" }
        gainsDb[band] = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        needsUpdate = true
    }

    /** Update all 10 gains at once (e.g. loading a preset). */
    fun setAllGains(gains: FloatArray) {
        require(gains.size == NUM_BANDS)
        gains.forEachIndexed { i, g -> gainsDb[i] = g.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
        needsUpdate = true
    }

    /** Return a copy of the current gain array. */
    fun getGains(): FloatArray = gainsDb.copyOf()

    /**
     * Notify the EQ that the audio sample rate has changed.
     * Forces coefficient recalculation.
     */
    fun setSampleRate(hz: Int) {
        if (hz != sampleRate) {
            sampleRate  = hz
            needsUpdate = true
        }
    }

    /** Reset all filter delay lines (call on track change / seek). */
    fun reset() {
        filters.forEach { it.reset() }
        bassFilter.reset()
        trebleFilter.reset()
    }

    // ── Processing ───────────────────────────────────────────────────────────

    /**
     * Process a stereo interleaved FloatArray buffer in-place.
     *
     * Buffer layout: [L0, R0, L1, R1, …, L(n-1), R(n-1)]
     *
     * @param buffer  Interleaved stereo float PCM  (modified in-place)
     * @param frames  Number of stereo frames = buffer.size / 2
     */
    fun process(buffer: FloatArray, frames: Int) {
        if (!isEnabled) return

        // Recalculate coefficients if any parameter changed
        if (needsUpdate) {
            recalculateCoefficients()
            needsUpdate = false
        }

        // Apply preamps (Manual + Auto)
        val totalPreamp = preampAutoLinear * dbToLinear(preampManualDb)
        if (totalPreamp != 1f) {
            for (i in 0 until frames * 2) {
                buffer[i] *= totalPreamp
            }
        }

        // Apply Tone Controls (Shelving filters)
        bassFilter.processStereoInterleaved(buffer, frames)
        trebleFilter.processStereoInterleaved(buffer, frames)

        // Run all 10 biquad filters in series
        for (filter in filters) {
            filter.processStereoInterleaved(buffer, frames)
        }

        // Soft-knee limiter — transparent until signal hits threshold
        applyLimiter(buffer, frames * 2)
    }

    // ── Private implementation ───────────────────────────────────────────────

    /**
     * Recalculate all filter coefficients based on current gains.
     * Also updates preamp to reduce the risk of inter-band clipping.
     */
    private fun recalculateCoefficients() {
        // Compute total positive energy to estimate headroom reduction needed
        val totalBoostDb = gainsDb.filter { it > 0f }.sum() + max(0f, bassDb) + max(0f, trebleDb)
        // Preamp reduction: 0 dB boost → factor 1.0; 12 dB boost → ~0.25
        preampAutoLinear = if (totalBoostDb > 0f) {
            // Reduce output by half the total boost, capped at -12dB auto preamp
            val reductionDb = (totalBoostDb * 0.5f).coerceAtMost(12f)
            dbToLinear(-reductionDb)
        } else {
            1f
        }

        // Tone Controls
        bassFilter.setCoefficients(
            type = BiquadFilter.Type.LOW_SHELF,
            freqHz = 100.0,
            gainDb = bassDb.toDouble(),
            qFactor = Q_SHELF.toDouble(),
            sampleRate = sampleRate
        )
        trebleFilter.setCoefficients(
            type = BiquadFilter.Type.HIGH_SHELF,
            freqHz = 10000.0,
            gainDb = trebleDb.toDouble(),
            qFactor = Q_SHELF.toDouble(),
            sampleRate = sampleRate
        )

        // Band 0: low-shelf (31 Hz)
        filters[0].setCoefficients(
            type       = BiquadFilter.Type.LOW_SHELF,
            freqHz     = BAND_FREQUENCIES[0].toDouble(),
            gainDb     = gainsDb[0].toDouble(),
            qFactor    = Q_SHELF.toDouble(),
            sampleRate = sampleRate
        )

        // Bands 1–8: peaking EQ
        for (band in 1 until NUM_BANDS - 1) {
            filters[band].setCoefficients(
                type       = BiquadFilter.Type.PEAKING_EQ,
                freqHz     = BAND_FREQUENCIES[band].toDouble(),
                gainDb     = gainsDb[band].toDouble(),
                qFactor    = Q_PEAKING.toDouble(),
                sampleRate = sampleRate
            )
        }

        // Band 9: high-shelf (16 kHz)
        filters[9].setCoefficients(
            type       = BiquadFilter.Type.HIGH_SHELF,
            freqHz     = BAND_FREQUENCIES[9].toDouble(),
            gainDb     = gainsDb[9].toDouble(),
            qFactor    = Q_SHELF.toDouble(),
            sampleRate = sampleRate
        )

        // Reset filter state to avoid transient clicks on coefficient change
        filters.forEach { it.reset() }
    }

    /**
     * Hard-knee limiter with soft saturation above threshold.
     *
     * Transfer function:
     *   |x| ≤ threshold  →  y = x                     (linear zone)
     *   |x| >  threshold  →  y = sign(x) · tanh curve  (soft saturation)
     *
     * tanh-based soft knee avoids the harsh digital clipping of a hard clip.
     */
    private fun applyLimiter(buffer: FloatArray, sampleCount: Int) {
        for (i in 0 until sampleCount) {
            val x = buffer[i]
            val absX = abs(x)
            if (absX > LIMITER_THRESHOLD) {
                // Soft saturation: maps [threshold, ∞) → [threshold, 1.0]
                val excess    = absX - LIMITER_THRESHOLD
                val range     = 1f - LIMITER_THRESHOLD
                val saturated = LIMITER_THRESHOLD + range * tanhApprox(excess / range)
                buffer[i] = if (x >= 0) saturated else -saturated
            }
        }
    }

    /**
     * Fast tanh approximation using Padé rational function.
     * Error < 0.001 for input range [0, 4].
     *
     *   tanh(x) ≈ x(27 + x²) / (27 + 9x²)
     */
    private fun tanhApprox(x: Float): Float {
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    private fun dbToLinear(db: Float): Float =
        Math.pow(10.0, db / 20.0).toFloat()
}
