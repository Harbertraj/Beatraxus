package com.beatraxus.app.engine

import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.SurroundLabKnob
import com.beatraxus.app.model.SurroundMode
import com.beatraxus.app.model.SurroundTuning
import com.beatraxus.app.model.surroundEffectiveTuning
import com.beatraxus.app.model.tuning
import com.beatraxus.app.model.withSurroundKnob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** The Upmix Lab values must really change what the upmixer does. */
class SurroundLabEngineTest {

    private val rate = 48_000
    private val frames = 9_600
    private val flat = List(8) { 0f }

    private fun sine(hz: Double): FloatArray = FloatArray(frames) { (0.5 * sin(2.0 * PI * hz * it / rate)).toFloat() }

    /** 5.1 out (FL FR FC LFE SL SR). [oppositePhase] puts the song's energy into the side signal. */
    private fun run(tuning: SurroundTuning, oppositePhase: Boolean): FloatArray {
        val left = sine(1000.0)
        val right = if (oppositePhase) FloatArray(frames) { -left[it] } else left
        val up = SurroundUpmixer(rate)
        up.configure(6, tuning, flat)
        val input = FloatArray(frames * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        val out = FloatArray(frames * 6)
        up.process(input, 2, frames, out)
        return out
    }

    private fun rms(out: FloatArray, channel: Int): Double {
        var sum = 0.0
        for (f in 0 until frames) sum += out[f * 6 + channel].toDouble().let { it * it }
        return sqrt(sum / frames)
    }

    @Test
    fun centerFocusControlsTheCenterSpeaker() {
        val movie = SurroundMode.MOVIE.tuning()
        assertTrue(rms(run(movie, false), 2) > 0.05)
        assertEquals(0.0, rms(run(movie.copy(centerAmount = 0f), false), 2), 1e-9)
        val more = rms(run(movie.copy(centerAmount = 1f), false), 2)
        assertTrue(more > rms(run(movie, false), 2))
    }

    @Test
    fun surroundWidthControlsTheSurroundSpeakers() {
        val movie = SurroundMode.MOVIE.tuning()
        assertTrue(rms(run(movie, true), 4) > 0.05)
        assertEquals(0.0, rms(run(movie.copy(surroundAmount = 0f), true), 4), 1e-9)
        assertEquals(0.0, rms(run(movie.copy(surroundAmount = 0f), true), 5), 1e-9)
    }

    @Test
    fun labValuesReachTheEngineThroughTheConfig() {
        val cfg = DspConfig(surroundMode = SurroundMode.MOVIE).withSurroundKnob(SurroundLabKnob.CENTER, 0f)
        assertEquals(0.0, rms(run(cfg.surroundEffectiveTuning(), false), 2), 1e-9)
    }

    @Test
    fun labOffSoundsExactlyLikeTheMode() {
        val viaConfig = run(DspConfig(surroundMode = SurroundMode.MUSIC).surroundEffectiveTuning(), true)
        val direct = run(SurroundMode.MUSIC.tuning(), true)
        assertTrue(direct.indices.all { direct[it] == viaConfig[it] })
    }
}
