package com.beatraxus.app.engine

import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.SurroundMoveMix
import com.beatraxus.app.model.SurroundTuning
import com.beatraxus.app.model.discreteChannelTrimIndex
import com.beatraxus.app.model.sanitizeSurroundTrims
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.tanh

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

    // --- 360° move (stereo sources): the whole song is panned around the room, see SurroundMoveMix ---
    private val moveTargetL = FloatArray(CHANNELS_UI)
    private val moveTargetR = FloatArray(CHANNELS_UI)
    private val moveCurrentL = FloatArray(CHANNELS_UI)
    private val moveCurrentR = FloatArray(CHANNELS_UI)
    private var moveCentreTarget = 0f
    private var moveCentreCurrent = 0f
    private var moveWanted = 0f
    private var moveBlend = 0f
    private var moveInitialised = false
    private val moveSmoothing: Float = (1.0 - exp(-1.0 / (MOVE_SMOOTH_SECONDS * sr))).toFloat()

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

    // --- smart steering: the matrix follows the song (centred voices -> centre, loose ambience -> surrounds) ---
    private var smartAmount = 0f
    private var corrLL = 0f
    private var corrRR = 0f
    private var corrLR = 0f
    private var smartCentre = 1f
    private var smartSurround = 1f
    private val corrDecay: Float = exp(-1.0 / (SMART_WINDOW_SECONDS * sr)).toFloat()
    private val smartSmoothing: Float = (1.0 - exp(-1.0 / (SMART_SMOOTH_SECONDS * sr))).toFloat()

    // --- bass management: 4th-order (two Butterworth sections) high-pass on every main speaker ---
    private var bassWanted = false
    private var bassBlend = 0f
    private val bassSmoothing: Float = (1.0 - exp(-1.0 / (0.02 * sr))).toFloat()
    private var hb0 = 1.0
    private var hb1 = 0.0
    private var hb2 = 0.0
    private var ha1 = 0.0
    private var ha2 = 0.0
    private val hpX1a = DoubleArray(MAX_SLOTS)
    private val hpX2a = DoubleArray(MAX_SLOTS)
    private val hpY1a = DoubleArray(MAX_SLOTS)
    private val hpY2a = DoubleArray(MAX_SLOTS)
    private val hpX1b = DoubleArray(MAX_SLOTS)
    private val hpX2b = DoubleArray(MAX_SLOTS)
    private val hpY1b = DoubleArray(MAX_SLOTS)
    private val hpY2b = DoubleArray(MAX_SLOTS)

    // --- distance calibration: one short delay line per output slot ---
    private val calLen = (sr * MAX_CAL_DELAY_SECONDS).toInt() + 4
    private val calBuf = Array(MAX_SLOTS) { FloatArray(calLen) }
    private val calDelay = IntArray(MAX_SLOTS)
    private var calOn = false
    private var calWp = 0

    // --- A/B hold (plain stereo on the front pair) and speaker test noise ---
    private var holdWanted = 0f
    private var holdBlend = 0f
    private val holdSmoothing: Float = (1.0 - exp(-1.0 / (0.008 * sr))).toFloat()
    private var testWanted = 0f
    private var testBlend = 0f
    private var testSlot = -1
    private val testSmoothing: Float = (1.0 - exp(-1.0 / (0.015 * sr))).toFloat()
    private var pink0 = 0f
    private var pink1 = 0f
    private var pink2 = 0f
    private var testLfe = 0f
    private var rngState = 0x2545F491
    private val testLfeCoef: Float = (1.0 - exp(-2.0 * PI * 120.0 / sr)).toFloat()

    // --- live level per speaker for the room view (UI order), peak with a fast decay ---
    private val meterBatchPeak = FloatArray(MAX_SLOTS)
    private val meterLevel = FloatArray(CHANNELS_UI)

    /**
     * Applies new matrix parameters / trims. Safe to call while audio is flowing (gains glide to their new
     * value); the very first call snaps so the stream does not fade in from silence.
     *
     * @param outChannels 6 (5.1) or 8 (7.1)
     * @param trimsDb eight values in UI order L R C SL SR BL BR SW
     * @param move optional 360° move routing for this layout; null = no move (the sound is exactly the upmix)
     */
    fun configure(
        outChannels: Int,
        tuning: SurroundTuning,
        trimsDb: List<Float>,
        move: SurroundMoveMix? = null,
        extras: SurroundExtras = SurroundExtras.NONE
    ) {
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
        configureMove(move)
        configureExtras(tuning, extras)
    }

    private fun configureExtras(tuning: SurroundTuning, extras: SurroundExtras) {
        smartAmount = if (extras.smartSteering.isNaN()) 0f else extras.smartSteering.coerceIn(0f, 1f)

        bassWanted = extras.bassManagement
        if (bassWanted) designHighPass(tuning.lfeCrossoverHz.toDouble().coerceIn(40.0, 250.0))

        val delays = extras.delaysMs
        val map = discreteChannelTrimIndex(outChannels)
        var anyDelay = false
        calDelay.fill(0)
        if (delays != null && map != null) {
            for (slot in map.indices) {
                val ms = delays.getOrNull(map[slot]) ?: 0f
                val samples = if (ms.isNaN() || ms < 0f) 0 else (ms * sr / 1000.0).toInt().coerceIn(0, calLen - 1)
                calDelay[slot] = samples
                if (samples > 0) anyDelay = true
            }
        }
        if (anyDelay && !calOn) {
            for (b in calBuf) b.fill(0f)
            calWp = 0
        }
        calOn = anyDelay

        val slot = testSlotFor(extras.testChannel, outChannels)
        if (slot >= 0) {
            testSlot = slot
            testWanted = 1f
        } else {
            testWanted = 0f
        }
        holdWanted = if (extras.holdOriginal) 1f else 0f
    }

    /** Output slot of the speaker with UI index [ui] in a layout of [channels], or -1 when that speaker does not exist. */
    private fun testSlotFor(ui: Int, channels: Int): Int {
        if (ui < 0) return -1
        val map = discreteChannelTrimIndex(channels) ?: return -1
        for (slot in map.indices) if (map[slot] == ui) return slot
        return -1
    }

    /** Copies the live level (0..1 peak) of every speaker, UI order L R C SL SR BL BR SW, into [dst]. */
    fun readLevels(dst: FloatArray) {
        val n = minOf(dst.size, CHANNELS_UI)
        for (i in 0 until n) dst[i] = meterLevel[i]
    }

    private fun configureMove(move: SurroundMoveMix?) {
        if (move == null) {
            moveWanted = 0f
            return
        }
        for (i in 0 until CHANNELS_UI) {
            moveTargetL[i] = finiteOrZero(move.left.getOrNull(i))
            moveTargetR[i] = finiteOrZero(move.right.getOrNull(i))
        }
        moveCentreTarget = finiteOrZero(move.centre).coerceIn(0f, 1f)
        // The first time the move is switched on the routing starts where it is aimed; the blend below fades it in.
        if (!moveInitialised || moveBlend <= 0f) {
            moveTargetL.copyInto(moveCurrentL)
            moveTargetR.copyInto(moveCurrentR)
            moveCentreCurrent = moveCentreTarget
            moveInitialised = true
        }
        moveWanted = 1f
    }

    /** Clears delay and filter memory (seek / flush) so old audio cannot leak into the next position. */
    fun reset() {
        delayBuf.fill(0f)
        writePos = 0
        sideAmbience = 0f
        backAmbience = 0f
        x1a = 0.0; x2a = 0.0; y1a = 0.0; y2a = 0.0
        x1b = 0.0; x2b = 0.0; y1b = 0.0; y2b = 0.0
        hpX1a.fill(0.0); hpX2a.fill(0.0); hpY1a.fill(0.0); hpY2a.fill(0.0)
        hpX1b.fill(0.0); hpX2b.fill(0.0); hpY1b.fill(0.0); hpY2b.fill(0.0)
        for (b in calBuf) b.fill(0f)
        calWp = 0
        corrLL = 0f; corrRR = 0f; corrLR = 0f
        smartCentre = 1f; smartSurround = 1f
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
        val smart = smartAmount
        val smartOn = smart > 0f
        val post = bassWanted || bassBlend > 0f || calOn || holdWanted > 0f || holdBlend > 0f ||
            testWanted > 0f || testBlend > 0f
        val sDelay = sideDelay
        val bDelay = backDelay
        val amb = ambienceCoef

        var wp = writePos
        var sAmb = sideAmbience
        var bAmb = backAmbience
        var bad = false

        for (f in 0 until frames) {
            val ib = f * inCh
            // A single NaN / Inf from the chain would poison the recursive state below (ambience low-pass, LFE
            // biquads, ...) for good, and the platform fold-down then spreads it into L/R = silence. Never let it in.
            var l = input[ib]
            if (l - l != 0f) { l = 0f; bad = true }
            var r = if (inCh > 1) input[ib + 1] else l
            if (r - r != 0f) { r = 0f; bad = true }
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

            var centreCur = centre
            var frontMidCur = frontMid
            var frontSideCur = frontSide
            var gSideCur = gSide
            var gBackCur = gBack
            if (smartOn) {
                // How centred (same level, same phase) and how ambient (uncorrelated) the song is right now.
                corrLL = corrDecay * corrLL + (1f - corrDecay) * l * l
                corrRR = corrDecay * corrRR + (1f - corrDecay) * r * r
                corrLR = corrDecay * corrLR + (1f - corrDecay) * l * r
                val power = corrLL + corrRR
                var centred = 0f
                var ambient = 0f
                if (power > 1e-9f) {
                    val geo = sqrt(corrLL * corrRR)
                    val rho = if (geo > 1e-9f) (corrLR / geo).coerceIn(-1f, 1f) else 0f
                    val balance = 2f * geo / power
                    centred = rho.coerceAtLeast(0f) * balance
                    ambient = 1f - abs(rho)
                }
                val wantCentre = (1f - smart) + smart * 2f * centred
                val wantSurround = (1f - smart) + smart * (0.5f + ambient)
                smartCentre += smartSmoothing * (wantCentre - smartCentre)
                smartSurround += smartSmoothing * (wantSurround - smartSurround)
                val kDyn = (k * smartCentre).coerceAtMost(0.5f)
                centreCur = SQRT2 * kDyn
                frontMidCur = 1f - kDyn
                val totalBase = gSide + gBack
                var mul = smartSurround
                if (totalBase > 0f && totalBase * mul > 1f) mul = 1f / totalBase
                gSideCur = gSide * mul
                gBackCur = gBack * mul
                frontSideCur = 1f - FOLD * totalBase * mul
            }
            val front = frontMidCur * m
            val frontL = front + frontSideCur * s
            val frontR = front - frontSideCur * s
            val centreSig = centreCur * m
            val surroundL = gSideCur * sAmb
            val surroundR = -surroundL

            val ob = f * outCh
            output[ob] = clamp(frontL * currentGain[IDX_L])
            output[ob + 1] = clamp(frontR * currentGain[IDX_R])
            output[ob + 2] = clamp(centreSig * currentGain[IDX_C])
            output[ob + 3] = clamp(lfe * currentGain[IDX_SW])
            if (sevenOne) {
                val backL = gBackCur * bAmb
                output[ob + 4] = clamp(backL * currentGain[IDX_BL])
                output[ob + 5] = clamp(-backL * currentGain[IDX_BR])
                output[ob + 6] = clamp(surroundL * currentGain[IDX_SL])
                output[ob + 7] = clamp(surroundR * currentGain[IDX_SR])
            } else {
                output[ob + 4] = clamp(surroundL * currentGain[IDX_SL])
                output[ob + 5] = clamp(surroundR * currentGain[IDX_SR])
            }

            // 360° move: fade to the panned song, or back to the plain upmix when the move is switched off.
            if (moveBlend != moveWanted) {
                moveBlend += moveSmoothing * (moveWanted - moveBlend)
                if (abs(moveWanted - moveBlend) < MOVE_SNAP) moveBlend = moveWanted
            }
            val blend = moveBlend
            if (blend > 0f) {
                for (g in 0 until CHANNELS_UI) {
                    moveCurrentL[g] += moveSmoothing * (moveTargetL[g] - moveCurrentL[g])
                    moveCurrentR[g] += moveSmoothing * (moveTargetR[g] - moveCurrentR[g])
                }
                moveCentreCurrent += moveSmoothing * (moveCentreTarget - moveCentreCurrent)
                // The centre speaker keeps its share of the dialogue, taken from the front pair like the normal upmix.
                val kc = k * moveCentreCurrent
                val lIn = l - kc * m
                val rIn = r - kc * m
                val keep = 1f - blend
                mixMoved(output, ob, IDX_L, keep, blend, lIn, rIn)
                mixMoved(output, ob, IDX_R, keep, blend, lIn, rIn)
                output[ob + 2] = output[ob + 2] * keep +
                    softClip(SQRT2 * kc * m * currentGain[IDX_C]) * blend
                if (sevenOne) {
                    mixMoved(output, ob, IDX_BL, keep, blend, lIn, rIn)
                    mixMoved(output, ob, IDX_BR, keep, blend, lIn, rIn)
                    mixMoved(output, ob, IDX_SL, keep, blend, lIn, rIn)
                    mixMoved(output, ob, IDX_SR, keep, blend, lIn, rIn)
                } else {
                    mixMoved(output, ob, IDX_SL, keep, blend, lIn, rIn)
                    mixMoved(output, ob, IDX_SR, keep, blend, lIn, rIn)
                }
            }

            if (post) postFrame(output, ob, outCh, l, r)
            for (slot in 0 until outCh) {
                val a = abs(output[ob + slot])
                if (a > meterBatchPeak[slot]) meterBatchPeak[slot] = a
            }
        }

        publishLevels(outCh)
        writePos = wp
        sideAmbience = sAmb
        backAmbience = bAmb

        // Also catches state that went non-finite on its own (overflow, bad trims): wipe it, keep playing.
        if (bad || !stateFinite()) {
            reset()
            recoveries++
        }
    }

    /** How many times the stage had to clear poisoned (non-finite) state since the last call; resets to 0. */
    fun consumeRecoveries(): Int {
        val n = recoveries
        recoveries = 0
        return n
    }

    private var recoveries = 0

    private fun stateFinite(): Boolean =
        sideAmbience - sideAmbience == 0f && backAmbience - backAmbience == 0f &&
            y1a - y1a == 0.0 && y2a - y2a == 0.0 && y1b - y1b == 0.0 && y2b - y2b == 0.0 &&
            smartCentre - smartCentre == 0f && smartSurround - smartSurround == 0f &&
            corrLL - corrLL == 0f && corrRR - corrRR == 0f && corrLR - corrLR == 0f

    /** End of a batch: decays the room-view levels and lifts them to the peaks of this batch. */
    private fun publishLevels(outCh: Int) {
        for (i in 0 until CHANNELS_UI) meterLevel[i] *= METER_DECAY
        val map = discreteChannelTrimIndex(outCh) ?: return
        for (slot in 0 until outCh) {
            val ui = map[slot]
            val v = meterBatchPeak[slot].coerceAtMost(1f)
            if (v > meterLevel[ui]) meterLevel[ui] = v
            meterBatchPeak[slot] = 0f
        }
    }

    /** Bass management, distance delays, A/B hold and speaker test noise for one finished output frame. */
    private fun postFrame(output: FloatArray, ob: Int, outCh: Int, l: Float, r: Float) {
        if (bassWanted || bassBlend > 0f) {
            val target = if (bassWanted) 1f else 0f
            if (bassBlend != target) {
                bassBlend += bassSmoothing * (target - bassBlend)
                if (abs(target - bassBlend) < MOVE_SNAP) bassBlend = target
            }
            for (slot in 0 until outCh) {
                if (slot == SLOT_LFE) continue
                val x = output[ob + slot]
                val y = highPass(slot, x)
                output[ob + slot] = x + (y - x) * bassBlend
            }
        }

        if (calOn) {
            val wp = calWp
            for (slot in 0 until outCh) {
                val buf = calBuf[slot]
                buf[wp] = output[ob + slot]
                var idx = wp - calDelay[slot]
                if (idx < 0) idx += calLen
                output[ob + slot] = buf[idx]
            }
            calWp = if (wp + 1 == calLen) 0 else wp + 1
        }

        if (holdBlend != holdWanted) {
            holdBlend += holdSmoothing * (holdWanted - holdBlend)
            if (abs(holdWanted - holdBlend) < MOVE_SNAP) holdBlend = holdWanted
        }
        val h = holdBlend
        if (h > 0f) {
            val keep = 1f - h
            for (slot in 0 until outCh) output[ob + slot] *= keep
            output[ob] += l * h
            output[ob + 1] += r * h
        }

        if (testBlend != testWanted) {
            testBlend += testSmoothing * (testWanted - testBlend)
            if (abs(testWanted - testBlend) < MOVE_SNAP) testBlend = testWanted
        }
        val tb = testBlend
        if (tb > 0f) {
            val tone = nextTestSample()
            val keep = 1f - tb
            for (slot in 0 until outCh) {
                val v = output[ob + slot] * keep
                output[ob + slot] = if (slot == testSlot) v + clamp(tone) * tb else v
            }
        }
    }

    /** Pink noise (Paul Kellet's filter) around -21 dBFS; the subwoofer gets it low-passed so it can be heard as a sub. */
    private fun nextTestSample(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        val white = x.toFloat() / 2.1474836E9f
        pink0 = 0.99765f * pink0 + white * 0.0990460f
        pink1 = 0.96300f * pink1 + white * 0.2965164f
        pink2 = 0.57000f * pink2 + white * 1.0526913f
        val pink = (pink0 + pink1 + pink2 + white * 0.1848f) * TEST_LEVEL
        if (testSlot != SLOT_LFE) return pink
        testLfe += testLfeCoef * (pink - testLfe)
        return testLfe * 3f
    }

    private fun highPass(slot: Int, input: Float): Float {
        val x = input.toDouble()
        val ya = hb0 * x + hb1 * hpX1a[slot] + hb2 * hpX2a[slot] - ha1 * hpY1a[slot] - ha2 * hpY2a[slot]
        hpX2a[slot] = hpX1a[slot]; hpX1a[slot] = x; hpY2a[slot] = hpY1a[slot]; hpY1a[slot] = ya
        val yb = hb0 * ya + hb1 * hpX1b[slot] + hb2 * hpX2b[slot] - ha1 * hpY1b[slot] - ha2 * hpY2b[slot]
        hpX2b[slot] = hpX1b[slot]; hpX1b[slot] = ya; hpY2b[slot] = hpY1b[slot]; hpY1b[slot] = yb
        return yb.toFloat()
    }

    /** RBJ Butterworth (Q = 1/sqrt2) high-pass; the same section is used twice (4th order, complements the LFE low-pass). */
    private fun designHighPass(cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / sr
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
        val a0 = 1.0 + alpha
        hb0 = (1.0 + cosW) / 2.0 / a0
        hb1 = -(1.0 + cosW) / a0
        hb2 = hb0
        ha1 = -2.0 * cosW / a0
        ha2 = (1.0 - alpha) / a0
    }

    /**
     * Puts the moved song on one speaker (UI index [ui]) and fades it against what the plain upmix already wrote there.
     * The output slot of a speaker depends on the layout, see the class comment.
     */
    private fun mixMoved(output: FloatArray, ob: Int, ui: Int, keep: Float, blend: Float, lIn: Float, rIn: Float) {
        val slot = outputSlot(ui)
        val moved = softClip((lIn * moveCurrentL[ui] + rIn * moveCurrentR[ui]) * currentGain[ui])
        output[ob + slot] = output[ob + slot] * keep + moved * blend
    }

    /** Position of a speaker (UI index) in the interleaved output frame of the current layout. */
    private fun outputSlot(ui: Int): Int = if (outChannels == 8) {
        when (ui) {
            IDX_L -> 0
            IDX_R -> 1
            IDX_BL -> 4
            IDX_BR -> 5
            IDX_SL -> 6
            else -> 7 // IDX_SR
        }
    } else {
        when (ui) {
            IDX_L -> 0
            IDX_R -> 1
            IDX_SL -> 4
            else -> 5 // IDX_SR
        }
    }

    /** Gentle limiter for the moved song: transparent below the knee, never above full scale. */
    private fun softClip(v: Float): Float {
        val a = abs(v)
        if (a <= SOFT_KNEE) return v
        val y = SOFT_KNEE + (1f - SOFT_KNEE) * tanh((a - SOFT_KNEE) / (1f - SOFT_KNEE))
        return if (v < 0f) -y else y
    }

    private fun finiteOrZero(v: Float?): Float = if (v == null || v.isNaN() || v.isInfinite()) 0f else v

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

    private fun clamp(v: Float): Float = if (v > 1f) 1f else if (v < -1f) -1f else if (v - v != 0f) 0f else v

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
        /** Moved-song gains glide slower than the trims: the orbit updates the position only 20 times a second. */
        private const val MOVE_SMOOTH_SECONDS = 0.05
        private const val MOVE_SNAP = 1e-4f
        private const val SOFT_KNEE = 0.85f
        private const val MAX_DELAY_SECONDS = 0.06
        private const val AMBIENCE_LOWPASS_HZ = 7_000.0

        private const val MAX_SLOTS = 8
        private const val SLOT_LFE = 3
        private const val SMART_WINDOW_SECONDS = 0.05
        private const val SMART_SMOOTH_SECONDS = 0.03
        private const val MAX_CAL_DELAY_SECONDS = 0.045
        private const val TEST_LEVEL = 0.06f
        private const val METER_DECAY = 0.82f
    }
}

/** dB offset of speaker [index] from the 360° move; 0 when move is off, missing or not a finite number. */
private fun positionOffset(positionDb: List<Float>?, index: Int): Float {
    val v = positionDb?.getOrNull(index) ?: return 0f
    return if (v.isNaN() || v.isInfinite()) 0f else v
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

    /**
     * @param channels 6 (5.1) or 8 (7.1), @param trimsDb eight values in UI order L R C SL SR BL BR SW
     * @param positionDb optional 360° move offsets (dB, same order); null = no move
     */
    fun configure(channels: Int, trimsDb: List<Float>, positionDb: List<Float>? = null) {
        val newMap = requireNotNull(discreteChannelTrimIndex(channels)) { "Unsupported discrete channel count: $channels" }
        this.channels = channels
        map = newMap
        val trims = sanitizeSurroundTrims(trimsDb)
        for (i in 0 until CHANNELS_UI) {
            targetGain[i] = 10.0.pow((trims[i] - DEFAULT_SURROUND_TRIMS_DB[i] + positionOffset(positionDb, i)) / 20.0).toFloat()
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
