package com.beatraxus.app.engine

import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.SurroundTuning
import com.beatraxus.app.model.discreteChannelTrimIndex
import com.beatraxus.app.model.sanitizeSurroundTrims
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Stereo (or mono) -> 5.1 / 7.1 upmixer. Pure Kotlin, no Android dependencies, allocation-free in [process].
 *
 * It runs as the LAST stage of the DSP pipeline (after EQ, spatial, reverb, limiter, resampler and dither),
 * on audio that is already at the output sample rate, so one instance is tied to one output rate.
 *
 * Output channel order follows the Android channel masks:
 *  - 6 ch (CHANNEL_OUT_5POINT1):          FL FR FC LFE SL SR
 *  - 8 ch (CHANNEL_OUT_7POINT1_SURROUND): FL FR FC LFE BL BR SL SR
 *
 * Matrix, with m = (L+R)/2 and s = (L-R)/2:
 *  - centre  = sqrt(2) * k * m                    (k = centreAmount / 2)
 *  - front   = (1-k) * m +/- (1 - FOLD*g) * s     (g = surroundAmount)
 *  - surround= +/- g_side * delayed, softened s   (7.1: split between the side and back pair)
 *  - LFE     = 4th-order low-pass of m
 *
 * The centre/surround shares are removed from the front pair by exactly the amount a standard ITU fold-down
 * (centre and surrounds at -3 dB) adds back, so if an output device can only play two channels and the
 * platform folds the 5.1/7.1 stream down, a mono-compatible signal comes out at its original level instead of
 * jumping louder. Per-channel trims (dB) are applied on top and are smoothed to avoid zipper noise.
 */
internal class SurroundUpmixer(sampleRate: Int) {

    private val sr: Double = sampleRate.coerceAtLeast(8_000).toDouble()

    var outChannels: Int = 6
        private set

    // --- matrix ---
    private var centerShare = 0f
    private var frontSideKeep = 1f
    private var sideGain = 0f
    private var backGain = 0f

    // --- trims (UI order: L R C SL SR BL BR SW), smoothed per sample ---
    private val currentGain = FloatArray(CHANNELS_UI) { 1f }
    private val targetGain = FloatArray(CHANNELS_UI) { 1f }
    private val smoothing: Float = (1.0 - exp(-1.0 / (SMOOTH_SECONDS * sr))).toFloat()
    private var configured = false

    // --- ambience delay (shared ring holding the side signal) ---
    private val delayLen = (sr * MAX_DELAY_SECONDS).toInt() + 4
    private val delayBuf = FloatArray(delayLen)
    private var writePos = 0
    private var sideDelay = 1
    private var backDelay = 1
    private val ambienceCoef: Float = (1.0 - exp(-2.0 * PI * AMBIENCE_LOWPASS_HZ / sr)).toFloat()
    private var sideAmbience = 0f
    private var backAmbience = 0f

    // --- LFE: two identical Butterworth low-pass biquads in series (4th order) ---
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1a = 0.0
    private var x2a = 0.0
    private var y1a = 0.0
    private var y2a = 0.0
    private var x1b = 0.0
    private var x2b = 0.0
    private var y1b = 0.0
    private var y2b = 0.0

    /**
     * Applies new matrix parameters / trims. Safe to call while audio is flowing (gains glide to their new
     * value); the very first call snaps so the stream does not fade in from silence.
     *
     * @param outChannels 6 (5.1) or 8 (7.1)
     * @param trimsDb eight values in UI order L R C SL SR BL BR SW
     */
    fun configure(outChannels: Int, tuning: SurroundTuning, trimsDb: List<Float>) {
        require(outChannels == 6 || outChannels == 8) { "Unsupported surround channel count: $outChannels" }
        this.outChannels = outChannels

        centerShare = tuning.centerAmount.coerceIn(0f, 1f) * 0.5f
        val total = tuning.surroundAmount.coerceIn(0f, 1f)
        val rear = if (outChannels == 8) tuning.rearShare.coerceIn(0f, 1f) else 0f
        sideGain = total * (1f - rear)
        backGain = total * rear
        frontSideKeep = 1f - FOLD * total

        sideDelay = msToSamples(tuning.surroundDelayMs)
        backDelay = msToSamples(tuning.rearDelayMs)

        designLowPass(tuning.lfeCrossoverHz.toDouble().coerceIn(40.0, 250.0))

        val trims = sanitizeSurroundTrims(trimsDb)
        for (i in 0 until CHANNELS_UI) {
            targetGain[i] = 10.0.pow(trims[i] / 20.0).toFloat()
        }
        if (!configured) {
            targetGain.copyInto(currentGain)
            configured = true
        }
    }

    /** Clears delay and filter memory (seek / flush) so old audio cannot leak into the next position. */
    fun reset() {
        delayBuf.fill(0f)
        writePos = 0
        sideAmbience = 0f
        backAmbience = 0f
        x1a = 0.0; x2a = 0.0; y1a = 0.0; y2a = 0.0
        x1b = 0.0; x2b = 0.0; y1b = 0.0; y2b = 0.0
    }

    /**
     * Upmixes [frames] frames from [input] (interleaved, [inChannels] = 1 or 2) into [output]
     * (interleaved, [outChannels] wide). [input] and [output] must be different arrays and [output] must hold
     * at least frames * outChannels samples.
     */
    fun process(input: FloatArray, inChannels: Int, frames: Int, output: FloatArray) {
        if (!configured || frames <= 0) return
        val inCh = inChannels.coerceAtLeast(1)
        val outCh = outChannels
        val sevenOne = outCh == 8

        val k = centerShare
        val centre = SQRT2 * k
        val frontMid = 1f - k
        val frontSide = frontSideKeep
        val gSide = sideGain
        val gBack = backGain
        val sDelay = sideDelay
        val bDelay = backDelay
        val amb = ambienceCoef

        var wp = writePos
        var sAmb = sideAmbience
        var bAmb = backAmbience

        for (f in 0 until frames) {
            val ib = f * inCh
            val l = input[ib]
            val r = if (inCh > 1) input[ib + 1] else l
            val m = 0.5f * (l + r)
            val s = 0.5f * (l - r)

            delayBuf[wp] = s
            var si = wp - sDelay
            if (si < 0) si += delayLen
            var bi = wp - bDelay
            if (bi < 0) bi += delayLen
            val sideDelayed = delayBuf[si]
            val backDelayed = delayBuf[bi]
            wp++
            if (wp == delayLen) wp = 0

            sAmb += amb * (sideDelayed - sAmb)
            bAmb += amb * (backDelayed - bAmb)

            // LFE: m -> biquad -> biquad
            val x = m.toDouble()
            val ya = b0 * x + b1 * x1a + b2 * x2a - a1 * y1a - a2 * y2a
            x2a = x1a; x1a = x; y2a = y1a; y1a = ya
            val yb = b0 * ya + b1 * x1b + b2 * x2b - a1 * y1b - a2 * y2b
            x2b = x1b; x1b = ya; y2b = y1b; y1b = yb
            val lfe = yb.toFloat() * LFE_BASE_GAIN

            // glide trims
            for (g in 0 until CHANNELS_UI) {
                currentGain[g] += smoothing * (targetGain[g] - currentGain[g])
            }

            val front = frontMid * m
            val frontL = front + frontSide * s
            val frontR = front - frontSide * s
            val centreSig = centre * m
            val surroundL = gSide * sAmb
            val surroundR = -surroundL

            val ob = f * outCh
            output[ob] = clamp(frontL * currentGain[IDX_L])
            output[ob + 1] = clamp(frontR * currentGain[IDX_R])
            output[ob + 2] = clamp(centreSig * currentGain[IDX_C])
            output[ob + 3] = clamp(lfe * currentGain[IDX_SW])
            if (sevenOne) {
                val backL = gBack * bAmb
                output[ob + 4] = clamp(backL * currentGain[IDX_BL])
                output[ob + 5] = clamp(-backL * currentGain[IDX_BR])
                output[ob + 6] = clamp(surroundL * currentGain[IDX_SL])
                output[ob + 7] = clamp(surroundR * currentGain[IDX_SR])
            } else {
                output[ob + 4] = clamp(surroundL * currentGain[IDX_SL])
                output[ob + 5] = clamp(surroundR * currentGain[IDX_SR])
            }
        }

        writePos = wp
        sideAmbience = sAmb
        backAmbience = bAmb
    }

    private fun msToSamples(ms: Float): Int =
        (ms.coerceIn(1f, MAX_DELAY_SECONDS.toFloat() * 1000f - 1f) * sr / 1000.0).toInt().coerceIn(1, delayLen - 1)

    /** RBJ Butterworth (Q = 1/sqrt2) low-pass; the same section is used twice. */
    private fun designLowPass(cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / sr
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
        val a0 = 1.0 + alpha
        b0 = (1.0 - cosW) / 2.0 / a0
        b1 = (1.0 - cosW) / a0
        b2 = b0
        a1 = -2.0 * cosW / a0
        a2 = (1.0 - alpha) / a0
    }

    private fun clamp(v: Float): Float = if (v > 1f) 1f else if (v < -1f) -1f else v

    companion object {
        private const val CHANNELS_UI = 8
        private const val IDX_L = 0
        private const val IDX_R = 1
        private const val IDX_C = 2
        private const val IDX_SL = 3
        private const val IDX_SR = 4
        private const val IDX_BL = 5
        private const val IDX_BR = 6
        private const val IDX_SW = 7

        private const val SQRT2 = 1.4142135f
        /** Gain a standard fold-down applies to centre / surround channels (-3 dB). */
        private const val FOLD = 0.70710677f
        private const val LFE_BASE_GAIN = 0.5f
        private const val BUTTERWORTH_Q = 0.70710678
        private const val SMOOTH_SECONDS = 0.012
        private const val MAX_DELAY_SECONDS = 0.06
        private const val AMBIENCE_LOWPASS_HZ = 7_000.0
    }
}

/**
 * Per-speaker trims for a stream that already is a real 5.1 / 7.1 mix (see [com.beatraxus.app.model.SurroundStage.DISCRETE]).
 *
 * Nothing is re-synthesised: every channel stays on its own position. The trims are applied relative to the
 * "Default" preset ([DEFAULT_SURROUND_TRIMS_DB]), so with the default trims the audio passes through untouched
 * (the fast path returns without writing a single sample). Pure Kotlin, allocation-free in [process].
 */
internal class SurroundTrimmer(sampleRate: Int) {

    private val sr: Double = sampleRate.coerceAtLeast(8_000).toDouble()
    private val smoothing: Float = (1.0 - exp(-1.0 / (SMOOTH_SECONDS * sr))).toFloat()
    private val currentGain = FloatArray(CHANNELS_UI) { 1f }
    private val targetGain = FloatArray(CHANNELS_UI) { 1f }
    private var map: IntArray = discreteChannelTrimIndex(6)!!
    private var configured = false

    var channels: Int = 6
        private set

    /** @param channels 6 (5.1) or 8 (7.1), @param trimsDb eight values in UI order L R C SL SR BL BR SW */
    fun configure(channels: Int, trimsDb: List<Float>) {
        val newMap = requireNotNull(discreteChannelTrimIndex(channels)) { "Unsupported discrete channel count: $channels" }
        this.channels = channels
        map = newMap
        val trims = sanitizeSurroundTrims(trimsDb)
        for (i in 0 until CHANNELS_UI) {
            targetGain[i] = 10.0.pow((trims[i] - DEFAULT_SURROUND_TRIMS_DB[i]) / 20.0).toFloat()
        }
        if (!configured) {
            targetGain.copyInto(currentGain)
            configured = true
        }
    }

    /** Scales [frames] interleaved frames of [data] in place. */
    fun process(data: FloatArray, frames: Int) {
        if (!configured || frames <= 0) return
        val ch = channels
        val positions = map

        var settled = true
        var unity = true
        for (g in 0 until CHANNELS_UI) {
            if (currentGain[g] != targetGain[g]) settled = false
            if (currentGain[g] != 1f) unity = false
        }
        if (settled && unity) return

        for (f in 0 until frames) {
            if (!settled) {
                var allDone = true
                for (g in 0 until CHANNELS_UI) {
                    val t = targetGain[g]
                    var c = currentGain[g]
                    c += smoothing * (t - c)
                    if (abs(t - c) < SNAP) c = t else allDone = false
                    currentGain[g] = c
                }
                settled = allDone
            }
            val base = f * ch
            for (c in 0 until ch) {
                val v = data[base + c] * currentGain[positions[c]]
                data[base + c] = if (v > 1f) 1f else if (v < -1f) -1f else v
            }
        }
    }

    private companion object {
        const val CHANNELS_UI = 8
        const val SMOOTH_SECONDS = 0.012
        const val SNAP = 1e-6f
    }
}
