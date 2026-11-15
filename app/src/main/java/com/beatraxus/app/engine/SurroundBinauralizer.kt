package com.beatraxus.app.engine

import com.beatraxus.app.model.discreteChannelTrimIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Headphone surround: folds a 5.1 / 7.1 stream (the output of [SurroundUpmixer]) down to two ears so the speakers are
 * heard in their own directions on headphones, earbuds or any stereo output.
 *
 * It is a parametric head model, not a measured HRTF set, so it is cheap, allocation-free and the same on every
 * device. Each virtual speaker reaches both ears with
 *  - a level split that grows with how far to the side it stands (constant power, so a centred sound stays centred),
 *  - the interaural time difference of a head (Woodworth model, up to about 0.66 ms) on the far ear,
 *  - head shadow: the far ear loses high frequencies, the more so the further to the side the speaker is,
 *  - a pinna cue: speakers behind the listener lose some highs before they reach either ear.
 * The subwoofer feeds both ears equally.
 *
 * Input order is the Android channel mask order of [SurroundUpmixer]: 6 ch = FL FR FC LFE SL SR, 8 ch = FL FR FC LFE BL BR SL SR.
 * Pure Kotlin, no Android dependencies.
 */
internal class SurroundBinauralizer(sampleRate: Int) {

    private val sr: Double = sampleRate.coerceAtLeast(8_000).toDouble()

    var inChannels: Int = 6
        private set

    // Per speaker, UI order L R C SL SR BL BR SW.
    private val nearGain = FloatArray(SPEAKERS)
    private val farGain = FloatArray(SPEAKERS)
    private val farDelaySamples = FloatArray(SPEAKERS)
    private val farLpCoef = FloatArray(SPEAKERS) { 1f }
    private val pinnaGain = FloatArray(SPEAKERS) { 1f }
    private val leftSide = BooleanArray(SPEAKERS)

    private val delayLen = (sr * MAX_ITD_SECONDS).toInt() + 8
    private val delayBuf = Array(SPEAKERS) { FloatArray(delayLen) }
    private var writePos = 0
    private val pinnaLp = FloatArray(SPEAKERS)
    private val farLp = FloatArray(SPEAKERS)
    private val pinnaCoef: Float = (1.0 - exp(-2.0 * PI * PINNA_CORNER_HZ / sr)).toFloat()

    private var slotToUi: IntArray = discreteChannelTrimIndex(6)!!

    // A/B hold: crossfade to the plain stereo the stage was fed with.
    private var holdWanted = 0f
    private var holdBlend = 0f
    private val holdSmoothing: Float = (1.0 - exp(-1.0 / (HOLD_SMOOTH_SECONDS * sr))).toFloat()

    /** Sets the virtual layout: 6 (5.1) or 8 (7.1). Clears the filter memory when the layout changes. */
    fun configure(channels: Int) {
        require(channels == 6 || channels == 8) { "Unsupported binaural channel count: $channels" }
        if (channels != inChannels || !configured) reset()
        inChannels = channels
        slotToUi = discreteChannelTrimIndex(channels)!!
        val sevenOne = channels == 8
        // Azimuth in degrees, clockwise from straight ahead. 5.1 surrounds stand a little behind the side (ITU 110°).
        val az = floatArrayOf(
            -30f, 30f, 0f,
            if (sevenOne) -90f else -110f, if (sevenOne) 90f else 110f,
            -150f, 150f,
            0f
        )
        for (i in 0 until SPEAKERS) {
            if (i == IDX_SW) continue
            val a = az[i]
            val lateral = asin(abs(sin(Math.toRadians(a.toDouble())))) // 0 (front / back) .. PI/2 (side)
            val side = sin(lateral).toFloat() // 0..1
            // Constant power split: centre is 0.707 / 0.707, a speaker at 90° is ~0.98 near / ~0.19 far.
            val phi = (PI / 4.0) * (1.0 + 0.75 * side)
            nearGain[i] = sin(phi).toFloat()
            farGain[i] = cos(phi).toFloat()
            val itd = HEAD_RADIUS_M / SPEED_OF_SOUND * (lateral + sin(lateral))
            farDelaySamples[i] = (itd * sr).toFloat().coerceIn(0f, (delayLen - 3).toFloat())
            farLpCoef[i] = if (side < 0.01f) 1f else {
                val fc = FAR_EAR_TOP_HZ * Math.pow(FAR_EAR_SIDE_HZ / FAR_EAR_TOP_HZ, side.toDouble())
                (1.0 - exp(-2.0 * PI * fc / sr)).toFloat().coerceIn(0.01f, 1f)
            }
            val absAz = abs(a)
            pinnaGain[i] = if (absAz <= 30f) 1f else (1f - 0.38f * ((absAz - 30f) / 120f)).coerceIn(0.5f, 1f)
            leftSide[i] = a < 0f
        }
        configured = true
    }

    private var configured = false

    /** True while the A/B button is held: fades to the plain stereo input. */
    fun setHold(hold: Boolean) {
        holdWanted = if (hold) 1f else 0f
    }

    fun reset() {
        for (b in delayBuf) b.fill(0f)
        pinnaLp.fill(0f)
        farLp.fill(0f)
        writePos = 0
    }

    /**
     * Folds [frames] frames of [input] ([inChannels] wide, interleaved) into [output] (stereo, interleaved, at least
     * frames * 2 values). [dry] is the stereo signal the upmixer was fed with; it is only read while the A/B hold is
     * (fading) on. [input], [output] and [dry] must be different arrays.
     */
    fun process(input: FloatArray, frames: Int, output: FloatArray, dry: FloatArray?) {
        if (!configured || frames <= 0) return
        val ch = inChannels
        val map = slotToUi
        var wp = writePos

        for (f in 0 until frames) {
            val base = f * ch
            var earL = 0f
            var earR = 0f
            for (slot in 0 until ch) {
                val ui = map[slot]
                val x = input[base + slot]
                if (ui == IDX_SW) {
                    earL += x * LFE_EAR_GAIN
                    earR += x * LFE_EAR_GAIN
                    continue
                }
                // Pinna: attenuate the highs of rear speakers (below the corner the signal passes untouched).
                val pl = pinnaLp[ui] + pinnaCoef * (x - pinnaLp[ui])
                pinnaLp[ui] = pl
                val s = pl + (x - pl) * pinnaGain[ui]

                val buf = delayBuf[ui]
                buf[wp] = s
                val d = farDelaySamples[ui]
                val whole = floor(d).toInt()
                val frac = d - whole
                var i0 = wp - whole
                if (i0 < 0) i0 += delayLen
                var i1 = i0 - 1
                if (i1 < 0) i1 += delayLen
                val a = buf[i0]
                val b = buf[i1]
                val far = a + (b - a) * frac

                val fl = farLp[ui] + farLpCoef[ui] * (far - farLp[ui])
                farLp[ui] = fl

                val nearSig = s * nearGain[ui]
                val farSig = fl * farGain[ui]
                if (leftSide[ui]) {
                    earL += nearSig
                    earR += farSig
                } else {
                    earR += nearSig
                    earL += farSig
                }
            }
            wp++
            if (wp == delayLen) wp = 0

            var outL = softClip(earL * OUTPUT_GAIN)
            var outR = softClip(earR * OUTPUT_GAIN)

            if (holdBlend != holdWanted) {
                holdBlend += holdSmoothing * (holdWanted - holdBlend)
                if (abs(holdWanted - holdBlend) < HOLD_SNAP) holdBlend = holdWanted
            }
            val h = holdBlend
            if (h > 0f && dry != null) {
                outL = outL * (1f - h) + dry[f * 2] * h
                outR = outR * (1f - h) + dry[f * 2 + 1] * h
            }
            output[f * 2] = outL
            output[f * 2 + 1] = outR
        }
        writePos = wp
    }

    private fun softClip(v: Float): Float {
        val a = abs(v)
        if (a <= SOFT_KNEE) return v
        val y = SOFT_KNEE + (1f - SOFT_KNEE) * tanh((a - SOFT_KNEE) / (1f - SOFT_KNEE))
        return if (v < 0f) -y else y
    }

    companion object {
        private const val SPEAKERS = 8
        private const val IDX_SW = 7

        private const val HEAD_RADIUS_M = 0.0875
        private const val SPEED_OF_SOUND = 343.0
        private const val MAX_ITD_SECONDS = 0.0009

        /** Far-ear low-pass: wide open for a speaker straight ahead, closing towards 1.8 kHz for one at the side. */
        private const val FAR_EAR_TOP_HZ = 20_000.0
        private const val FAR_EAR_SIDE_HZ = 1_800.0
        private const val PINNA_CORNER_HZ = 3_500.0

        /** Every speaker reaches both ears; this keeps a mono-compatible upmix at the level of the plain stereo. */
        private const val OUTPUT_GAIN = 0.78f
        private const val LFE_EAR_GAIN = 0.707f
        private const val SOFT_KNEE = 0.85f
        private const val HOLD_SMOOTH_SECONDS = 0.008
        private const val HOLD_SNAP = 1e-4f
    }
}
