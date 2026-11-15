package com.beatraxus.app.engine

import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.surroundEffectiveTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SurroundExtrasEngineTest {
    private val sr = 48_000

    private fun tone(freq: Double, amp: Double, frames: Int = sr): FloatArray =
        FloatArray(frames * 2) { i -> (amp * sin(2 * PI * freq * (i / 2) / sr)).toFloat() }

    private fun rms(x: FloatArray, stride: Int, ch: Int): Double {
        val n = x.size / stride
        return sqrt((0 until n).sumOf { (x[it * stride + ch] * x[it * stride + ch]).toDouble() } / n)
    }

    private fun upmixer(channels: Int, extras: SurroundExtras) = SurroundUpmixer(sr).also {
        it.configure(channels, DspConfig().surroundEffectiveTuning(), DEFAULT_SURROUND_TRIMS_DB, null, extras)
    }

    @Test fun noExtrasKeepsOutputFinite() {
        val up = upmixer(6, SurroundExtras.NONE)
        val out = FloatArray(sr * 6)
        up.process(tone(1000.0, 0.5), 2, sr, out)
        assertTrue(out.none { it.isNaN() || abs(it) > 1.5f })
    }

    @Test fun testNoiseGoesToOneSpeakerOnly() {
        // 5.1 slot order FL FR FC LFE SL SR: UI index 2 (C) is slot 2.
        val up = upmixer(6, SurroundExtras(testChannel = 2))
        val out = FloatArray(sr * 6)
        up.process(FloatArray(sr * 2), 2, sr, out)
        val c = rms(out, 6, 2)
        assertTrue("centre has noise", c > 0.02)
        assertTrue("fronts are quiet", rms(out, 6, 0) < c / 5 && rms(out, 6, 1) < c / 5)
    }

    @Test fun holdOriginalPlaysPlainStereoOnTheFrontPair() {
        val up = upmixer(6, SurroundExtras(holdOriginal = true))
        val input = tone(1000.0, 0.4)
        val out = FloatArray(sr * 6)
        up.process(input, 2, sr, out)
        // After the fade, L and R follow the input and the rest is silent.
        val tail = sr - 4800
        var maxErr = 0.0
        for (f in tail until sr) maxErr = maxOf(maxErr, abs(out[f * 6] - input[f * 2]).toDouble())
        assertTrue("front L equals input (err $maxErr)", maxErr < 0.01)
        assertTrue("centre silent", rms(out.copyOfRange(tail * 6, sr * 6), 6, 2) < 0.005)
    }

    @Test fun bassManagementRemovesLowEndFromMains() {
        val low = tone(40.0, 0.5)
        val off = FloatArray(sr * 6).also { upmixer(6, SurroundExtras.NONE).process(low, 2, sr, it) }
        val on = FloatArray(sr * 6).also { upmixer(6, SurroundExtras(bassManagement = true)).process(low, 2, sr, it) }
        val from = sr / 2 * 6
        assertTrue(rms(on.copyOfRange(from, on.size), 6, 0) < rms(off.copyOfRange(from, off.size), 6, 0) * 0.3)
    }

    @Test fun calibrationDelayShiftsASpeaker() {
        val delays = FloatArray(8).also { it[0] = 5f }
        val input = tone(500.0, 0.3)
        val flat = FloatArray(sr * 6).also { upmixer(6, SurroundExtras.NONE).process(input, 2, sr, it) }
        val del = FloatArray(sr * 6).also { upmixer(6, SurroundExtras(delaysMs = delays)).process(input, 2, sr, it) }
        val shift = (5f * sr / 1000f).toInt()
        for (f in 2000 until 4000) assertEquals(flat[(f - shift) * 6], del[f * 6], 1e-4f)
    }

    @Test fun smartSteeringStaysBoundedAndPullsCentredSoundToCentre() {
        val mono = tone(800.0, 0.4).also { for (f in 0 until sr) it[f * 2 + 1] = it[f * 2] }
        val plain = FloatArray(sr * 6).also { upmixer(6, SurroundExtras.NONE).process(mono, 2, sr, it) }
        val smart = FloatArray(sr * 6).also { upmixer(6, SurroundExtras(smartSteering = 1f)).process(mono, 2, sr, it) }
        assertTrue(smart.none { it.isNaN() || abs(it) > 1.5f })
        val from = sr / 2 * 6
        assertTrue(rms(smart.copyOfRange(from, smart.size), 6, 2) >= rms(plain.copyOfRange(from, plain.size), 6, 2))
    }

    @Test fun metersFollowTheSound() {
        val up = upmixer(6, SurroundExtras.NONE)
        up.process(tone(1000.0, 0.5), 2, sr, FloatArray(sr * 6))
        val lv = FloatArray(8)
        up.readLevels(lv)
        assertTrue(lv[0] > 0.1f && lv[1] > 0.1f)
        assertEquals(0f, lv[5], 1e-6f) // back speakers do not exist in 5.1
    }

    @Test fun binauralKeepsCentredSoundCentredAndFinite() {
        val up = upmixer(6, SurroundExtras.NONE)
        val six = FloatArray(sr * 6)
        val st = tone(1000.0, 0.4).also { for (f in 0 until sr) it[f * 2 + 1] = it[f * 2] }
        up.process(st, 2, sr, six)
        val bz = SurroundBinauralizer(sr).also { it.configure(6) }
        val two = FloatArray(sr * 2)
        bz.process(six, sr, two, st)
        assertTrue(two.none { it.isNaN() || abs(it) > 1f })
        assertEquals(rms(two, 2, 0), rms(two, 2, 1), 0.02)
    }

    @Test fun binauralSideSpeakerIsLouderInTheNearEar() {
        val bz = SurroundBinauralizer(sr).also { it.configure(6) }
        // 5.1 slot 4 = surround left; feed only that speaker.
        val six = FloatArray(sr * 6) { i -> if (i % 6 == 4) (0.4 * sin(2 * PI * 2000.0 * (i / 6) / sr)).toFloat() else 0f }
        val two = FloatArray(sr * 2)
        bz.process(six, sr, two, null)
        assertTrue(rms(two, 2, 0) > rms(two, 2, 1) * 1.5)
    }

    @Test fun upscalerAddsHighsAboveCutoffAndKeepsTheRest() {
        val up = AudioUpscaler(sr).also { it.configure(1f, 16_000f) }
        val data = FloatArray(sr * 2) { i ->
            val t = (i / 2).toDouble() / sr
            (0.3 * sin(2 * PI * 9000 * t) + 0.2 * sin(2 * PI * 11000 * t)).toFloat()
        }
        val before = data.copyOf()
        up.process(data, sr)
        fun mag(x: FloatArray, f: Double): Double {
            var re = 0.0; var im = 0.0
            for (k in 0 until sr) { re += x[k * 2] * cos(2 * PI * f * k / sr); im += x[k * 2] * sin(2 * PI * f * k / sr) }
            return 2 * sqrt(re * re + im * im) / sr
        }
        assertTrue(mag(before, 18_000.0) < 0.001)
        assertTrue(mag(data, 18_000.0) > 0.02)
        assertEquals(0.3, mag(data, 9_000.0), 0.01)
    }

    @Test fun upscalerLeavesSilenceSilent() {
        val up = AudioUpscaler(sr).also { it.configure(1f, 16_000f) }
        val data = FloatArray(sr * 2)
        up.process(data, sr)
        assertTrue(data.all { it == 0f })
    }
}
