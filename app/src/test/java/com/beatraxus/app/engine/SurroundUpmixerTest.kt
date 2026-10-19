package com.beatraxus.app.engine

import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.SurroundMode
import com.beatraxus.app.model.SurroundStage
import com.beatraxus.app.model.resolveSurroundStage
import com.beatraxus.app.model.toSurroundEngineConfig
import com.beatraxus.app.model.resolveSurroundOutputChannels
import com.beatraxus.app.model.sanitizeSurroundTrims
import com.beatraxus.app.model.tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SurroundUpmixerTest {

    private val rate = 48_000
    private val flat = List(8) { 0f }

    private fun upmix(
        outChannels: Int,
        mode: SurroundMode,
        trims: List<Float>,
        left: FloatArray,
        right: FloatArray
    ): FloatArray {
        val up = SurroundUpmixer(rate)
        up.configure(outChannels, mode.tuning(), trims)
        val frames = left.size
        val input = FloatArray(frames * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        val out = FloatArray(frames * outChannels)
        up.process(input, 2, frames, out)
        return out
    }

    private fun rms(out: FloatArray, channels: Int, ch: Int, from: Int = 0): Double {
        var sum = 0.0
        var n = 0
        var f = from
        while (f * channels + ch < out.size) {
            val v = out[f * channels + ch].toDouble()
            sum += v * v
            n++
            f++
        }
        return sqrt(sum / n)
    }

    private fun sine(freq: Double, frames: Int, amp: Float = 0.5f) =
        FloatArray(frames) { (amp * sin(2.0 * PI * freq * it / rate)).toFloat() }

    @Test
    fun outputHasRequestedWidthAndFrameCount() {
        val x = sine(1000.0, 4800)
        assertEquals(4800 * 6, upmix(6, SurroundMode.MOVIE, flat, x, x).size)
        assertEquals(4800 * 8, upmix(8, SurroundMode.MOVIE, flat, x, x).size)
    }

    @Test
    fun monoSourceFeedsCentreAndNoSurrounds() {
        val x = sine(1000.0, 9600)
        val out = upmix(6, SurroundMode.MOVIE, flat, x, x)
        // centre carries signal, the side-difference channels (surrounds) stay silent
        assertTrue(rms(out, 6, 2) > 0.05)
        assertEquals(0.0, rms(out, 6, 4), 1e-6)
        assertEquals(0.0, rms(out, 6, 5), 1e-6)
    }

    @Test
    fun hardPannedSourceReachesTheMatchingSurround() {
        val x = sine(1000.0, 9600)
        val silence = FloatArray(x.size)
        val out = upmix(6, SurroundMode.MOVIE, flat, x, silence)
        // let the ambience delay (12 ms) fill before measuring
        val skip = 2400
        val sl = rms(out, 6, 4, skip)
        val sr = rms(out, 6, 5, skip)
        assertTrue("surround must carry the side signal", sl > 0.02)
        // opposite phase, equal level
        assertEquals(sl, sr, 1e-4)
    }

    @Test
    fun lfeKeepsBassAndRejectsTreble() {
        val bass = sine(50.0, 24_000)
        val treble = sine(8000.0, 24_000)
        val bassOut = upmix(6, SurroundMode.MOVIE, flat, bass, bass)
        val trebleOut = upmix(6, SurroundMode.MOVIE, flat, treble, treble)
        val bassLfe = rms(bassOut, 6, 3, 4800)
        val trebleLfe = rms(trebleOut, 6, 3, 4800)
        assertTrue("bass should pass the LFE low-pass", bassLfe > 0.1)
        assertTrue("treble should be heavily attenuated", trebleLfe < bassLfe * 0.01)
    }

    @Test
    fun sevenOneAddsBackPairAndKeepsSidesSeparate() {
        val x = sine(1000.0, 14_400)
        val silence = FloatArray(x.size)
        val out = upmix(8, SurroundMode.GAME, flat, x, silence)
        // 7.1 order: FL FR FC LFE BL BR SL SR
        assertTrue(rms(out, 8, 4, 4800) > 0.01)   // BL
        assertTrue(rms(out, 8, 6, 4800) > 0.01)   // SL
        val five = upmix(6, SurroundMode.GAME, flat, x, silence)
        // with a back pair present the side pair gets a smaller share than in 5.1
        assertTrue(rms(out, 8, 6, 4800) < rms(five, 6, 4, 4800))
    }

    @Test
    fun trimsScaleTheirOwnChannelOnly() {
        val x = sine(1000.0, 9600)
        val base = upmix(6, SurroundMode.MOVIE, flat, x, x)
        val trims = flat.toMutableList().also { it[2] = -6f } // centre
        val trimmed = upmix(6, SurroundMode.MOVIE, trims, x, x)
        val ratio = rms(trimmed, 6, 2) / rms(base, 6, 2)
        assertEquals(0.5012, ratio, 0.01)                       // -6 dB
        assertEquals(rms(base, 6, 0), rms(trimmed, 6, 0), 1e-9) // front untouched
    }

    @Test
    fun foldDownToStereoKeepsMonoLevel() {
        // ITU fold-down: L' = FL + 0.707*FC + 0.707*SL (+ LFE not folded). Mono content must keep its level so a
        // stereo-only device that folds our stream down does not get louder.
        val x = sine(1000.0, 9600)
        val out = upmix(6, SurroundMode.MOVIE, flat, x, x)
        var maxErr = 0.0
        for (f in 2400 until x.size) {
            val b = f * 6
            val folded = out[b] + 0.70710678 * out[b + 2] + 0.70710678 * out[b + 4]
            maxErr = maxOf(maxErr, abs(folded - x[f]))
        }
        assertTrue("fold-down must reproduce the mono source, max error $maxErr", maxErr < 0.01)
    }

    @Test
    fun neverExceedsFullScale() {
        val loud = FloatArray(4800) { if (it % 2 == 0) 1f else -1f }
        val trims = List(8) { 12f }
        val out = upmix(8, SurroundMode.GAME, trims, loud, loud.reversedArray())
        assertTrue(out.all { it in -1f..1f })
        assertTrue(out.none { it.isNaN() })
    }

    @Test
    fun monoInputIsAccepted() {
        val up = SurroundUpmixer(rate)
        up.configure(6, SurroundMode.MUSIC.tuning(), flat)
        val x = sine(1000.0, 4800)
        val out = FloatArray(4800 * 6)
        up.process(x, 1, 4800, out)
        assertTrue(rms(out, 6, 2) > 0.01)
    }

    @Test
    fun resetClearsAmbienceTail() {
        val up = SurroundUpmixer(rate)
        up.configure(6, SurroundMode.MOVIE.tuning(), flat)
        val x = sine(1000.0, 4800)
        val silence = FloatArray(4800)
        val input = FloatArray(4800 * 2) { if (it % 2 == 0) x[it / 2] else 0f }
        up.process(input, 2, 4800, FloatArray(4800 * 6))
        up.reset()
        val out = FloatArray(4800 * 6)
        up.process(FloatArray(4800 * 2), 2, 4800, out)
        assertEquals(0.0, rms(out, 6, 4), 1e-9)
        assertEquals(0.0, rms(out, 6, 3), 1e-9)
        assertTrue(silence.all { it == 0f })
    }

    @Test
    fun sanitizeTrimsAlwaysReturnsEightValuesInRange() {
        assertEquals(8, sanitizeSurroundTrims(null).size)
        assertEquals(DEFAULT_SURROUND_TRIMS_DB, sanitizeSurroundTrims(emptyList()))
        val fixed = sanitizeSurroundTrims(listOf(99f, -99f, Float.NaN))
        assertEquals(12f, fixed[0], 0f)
        assertEquals(-12f, fixed[1], 0f)
        assertEquals(DEFAULT_SURROUND_TRIMS_DB[2], fixed[2], 0f)
        assertEquals(8, fixed.size)
    }

    // ── channel resolution: this is what keeps "toggle off = output exactly as before" true ────────────────

    private fun resolve(
        config: DspConfig,
        source: Int = 2,
        dop: Boolean = false,
        device: Int = 2
    ) = resolveSurroundOutputChannels(config, source, dop, device)

    @Test
    fun toggleOffLeavesChannelCountUntouched() {
        val off = DspConfig(surroundEnabled = false, surroundLayout = SurroundLayout.SEVEN_ONE)
        assertEquals(2, resolve(off))
        assertEquals(1, resolve(off, source = 1))
        assertEquals(6, resolve(off, source = 6))
    }

    @Test
    fun layoutsMapToTheRightWidth() {
        val on = DspConfig(surroundEnabled = true)
        assertEquals(6, resolve(on.copy(surroundLayout = SurroundLayout.FIVE_ONE)))
        assertEquals(8, resolve(on.copy(surroundLayout = SurroundLayout.SEVEN_ONE)))
        assertEquals(2, resolve(on.copy(surroundLayout = SurroundLayout.STEREO)))
        assertEquals(6, resolve(on.copy(surroundLayout = SurroundLayout.FIVE_ONE), source = 1))
    }

    @Test
    fun autoFollowsDeviceCapability() {
        val auto = DspConfig(surroundEnabled = true, surroundLayout = SurroundLayout.AUTO)
        assertEquals(2, resolve(auto, device = 2))
        assertEquals(6, resolve(auto, device = 6))
        assertEquals(8, resolve(auto, device = 8))
    }

    @Test
    fun surroundIsSkippedWhereItMustNotApply() {
        val on = DspConfig(surroundEnabled = true, surroundLayout = SurroundLayout.FIVE_ONE)
        assertEquals(2, resolve(on.copy(bitPerfectEnabled = true)))
        assertEquals(2, resolve(on.copy(bypassAll = true)))
        assertEquals(2, resolve(on, dop = true))
        // already multichannel sources keep their own layout
        assertEquals(6, resolve(on.copy(surroundLayout = SurroundLayout.SEVEN_ONE), source = 6))
        assertEquals(8, resolve(on, source = 8))
        assertFalse(resolve(on) == 2)
    }

    @Test
    fun stageIsDiscreteForRealMultichannelAndUpmixForStereo() {
        val on = DspConfig(surroundEnabled = true)
        assertEquals(SurroundStage.UPMIX, resolveSurroundStage(on, 2, false, 6))
        assertEquals(SurroundStage.OFF, resolveSurroundStage(on, 2, false, 2))
        assertEquals(SurroundStage.DISCRETE, resolveSurroundStage(on, 6, false, 6))
        assertEquals(SurroundStage.DISCRETE, resolveSurroundStage(on, 8, false, 8))
        assertEquals(SurroundStage.OFF, resolveSurroundStage(on, 6, false, 2))
        assertEquals(SurroundStage.OFF, resolveSurroundStage(on.copy(bypassAll = true), 6, false, 6))
    }

    @Test
    fun surroundEngineConfigIsNeutralButKeepsOutputSettings() {
        val user = DspConfig(
            eqEnabled = true, reverbEnabled = true, limiterEnabled = true, bitPerfectEnabled = true,
            surroundEnabled = false, surroundLayout = SurroundLayout.STEREO, ditherEnabled = true
        )
        val cfg = user.toSurroundEngineConfig()
        assertFalse(cfg.eqEnabled)
        assertFalse(cfg.reverbEnabled)
        assertFalse(cfg.limiterEnabled)
        assertFalse(cfg.bitPerfectEnabled)
        assertTrue(cfg.surroundEnabled)
        assertEquals(SurroundLayout.FIVE_ONE, cfg.surroundLayout)
        assertTrue(cfg.ditherEnabled)
        assertEquals(6, resolveSurroundOutputChannels(cfg, 2, false, 2))
    }

    @Test
    fun discreteTrimmerLeavesDefaultTrimsUntouched() {
        val t = SurroundTrimmer(rate)
        t.configure(6, DEFAULT_SURROUND_TRIMS_DB)
        val data = FloatArray(6 * 100) { (it % 7) / 10f - 0.3f }
        val copy = data.copyOf()
        t.process(data, 100)
        assertTrue(data.contentEquals(copy))
    }

    @Test
    fun discreteTrimmerScalesOnlyTheTrimmedSpeaker() {
        val t = SurroundTrimmer(rate)
        // centre 6 dB above Default; everything else at Default
        val trims = DEFAULT_SURROUND_TRIMS_DB.toMutableList().also { it[2] = it[2] + 6f }
        t.configure(6, trims)
        val frames = 4800
        val data = FloatArray(6 * frames) { 0.1f }
        t.process(data, frames)
        val last = (frames - 1) * 6
        assertEquals(0.1f, data[last], 1e-6f)            // FL untouched
        assertEquals(0.1f, data[last + 3], 1e-6f)        // LFE untouched
        assertEquals(0.1f * 1.9953f, data[last + 2], 1e-3f) // FC +6 dB
    }

    @Test
    fun activeEffectsListMentionsSurroundOnlyWhenItReallyApplies() {
        val on = DspConfig(surroundEnabled = true, surroundLayout = SurroundLayout.SEVEN_ONE)
        assertTrue("7.1 Surround" in on.activeEffects())
        assertFalse(on.copy(surroundEnabled = false).activeEffects().any { it.contains("Surround") })
        assertFalse(on.copy(bitPerfectEnabled = true).activeEffects().any { it.contains("Surround") })
        assertFalse(on.copy(surroundLayout = SurroundLayout.STEREO).activeEffects().any { it.contains("Surround") })
    }
}
