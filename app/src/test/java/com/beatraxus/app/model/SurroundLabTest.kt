package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundLabTest {

    // ── Upmix Lab ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun labOffIsExactlyTheModeTuning() {
        for (mode in SurroundMode.entries) {
            assertEquals(mode.tuning(), DspConfig(surroundMode = mode).surroundEffectiveTuning())
        }
        // Stored values are ignored while the switch is off.
        val stale = DspConfig(surroundMode = SurroundMode.GAME, surroundTune = SurroundMode.MUSIC.tuning())
        assertEquals(SurroundMode.GAME.tuning(), stale.surroundEffectiveTuning())
    }

    @Test
    fun turningTheLabOnStartsFromTheModeSoNothingJumps() {
        val on = DspConfig(surroundMode = SurroundMode.MUSIC).withSurroundLab(true)
        assertTrue(on.surroundTuneEnabled)
        assertEquals(SurroundMode.MUSIC.tuning(), on.surroundEffectiveTuning())
        assertFalse(on.withSurroundLab(false).surroundTuneEnabled)
    }

    @Test
    fun movingOneKnobTakesOverAndKeepsTheRestOfTheMode() {
        val c = DspConfig(surroundMode = SurroundMode.GAME).withSurroundKnob(SurroundLabKnob.CENTER, 0.8f)
        assertTrue(c.surroundTuneEnabled)
        val t = c.surroundEffectiveTuning()
        assertEquals(0.8f, t.centerAmount, 1e-4f)
        assertEquals(SurroundMode.GAME.tuning().surroundAmount, t.surroundAmount, 0f)
        assertEquals(SurroundMode.GAME.tuning().lfeCrossoverHz, t.lfeCrossoverHz, 0f)
    }

    @Test
    fun ambienceKnobMovesTheBackDelayWithIt() {
        val t = SurroundMode.MOVIE.tuning().withKnob(SurroundLabKnob.AMBIENCE, 20f)
        assertEquals(20f, t.surroundDelayMs, 1e-4f)
        assertEquals(37f, t.rearDelayMs, 0.01f)
        val big = SurroundMode.MOVIE.tuning().withKnob(SurroundLabKnob.AMBIENCE, 40f)
        assertTrue(big.rearDelayMs <= 58f)
    }

    @Test
    fun knobsAreClampedAndSnappedToTheirStep() {
        val base = SurroundMode.MOVIE.tuning()
        assertEquals(250f, base.withKnob(SurroundLabKnob.BASS, 999f).lfeCrossoverHz, 0f)
        assertEquals(40f, base.withKnob(SurroundLabKnob.BASS, -5f).lfeCrossoverHz, 0f)
        assertEquals(110f, base.withKnob(SurroundLabKnob.BASS, 112f).lfeCrossoverHz, 0f)
        assertEquals(0.35f, base.withKnob(SurroundLabKnob.CENTER, 0.33f).centerAmount, 1e-4f)
        assertEquals(0f, base.withKnob(SurroundLabKnob.CENTER, Float.NaN).centerAmount, 0f)
        assertEquals(1f, base.withKnob(SurroundLabKnob.WIDTH, 7f).surroundAmount, 0f)
    }

    @Test
    fun everyKnobReadsBackWhatWasSet() {
        var t = SurroundMode.MOVIE.tuning()
        for (knob in SurroundLabKnob.entries) {
            val target = (knob.min + knob.max) / 2f
            t = t.withKnob(knob, target)
            assertEquals(knob.name, target, t.knobValue(knob), knob.step)
        }
    }

    @Test
    fun brokenStoredValuesBecomeSafeNumbers() {
        val s = sanitizeSurroundTuning(SurroundTuning(Float.NaN, 9f, -3f, Float.POSITIVE_INFINITY, Float.NaN, 500f))
        assertEquals(0.35f, s.centerAmount, 0f)
        assertEquals(1f, s.surroundAmount, 0f)
        assertEquals(0f, s.rearShare, 0f)
        assertEquals(110f, s.lfeCrossoverHz, 0f)
        assertEquals(12f, s.surroundDelayMs, 0f)
        assertEquals(58f, s.rearDelayMs, 0f)
    }

    @Test
    fun savedFormRoundTrips() {
        for (mode in SurroundMode.entries) {
            assertEquals(mode.tuning(), deserializeSurroundTuning(serializeSurroundTuning(mode.tuning())))
        }
        assertEquals("", serializeSurroundTuning(null))
        assertNull(deserializeSurroundTuning(null))
        assertNull(deserializeSurroundTuning(""))
        assertNull(deserializeSurroundTuning("1,2,3"))
        assertNull(deserializeSurroundTuning("a,b,c,d,e,f"))
    }

    @Test
    fun resetForgetsTheUsersValues() {
        val c = DspConfig().withSurroundKnob(SurroundLabKnob.BASS, 200f).withSurroundLabReset()
        assertFalse(c.surroundTuneEnabled)
        assertNull(c.surroundTune)
        assertEquals(SurroundMode.MOVIE.tuning(), c.surroundEffectiveTuning())
    }

    @Test
    fun pickingAPresetHandsControlBackToTheMode() {
        val lab = DspConfig(surroundMode = SurroundMode.MOVIE).withSurroundKnob(SurroundLabKnob.CENTER, 1f)
        val cinema = lab.withSurroundPreset("Cinema")
        assertFalse(cinema.surroundTuneEnabled)
        assertEquals(SurroundMode.MOVIE.tuning(), cinema.surroundEffectiveTuning())
        val concert = lab.withSurroundPreset("Concert")
        assertEquals(SurroundMode.MUSIC.tuning(), concert.surroundEffectiveTuning())
    }

    @Test
    fun videoSurroundEngineKeepsTheLab() {
        val cfg = DspConfig().withSurroundKnob(SurroundLabKnob.WIDTH, 0.9f)
        val engine = cfg.toSurroundEngineConfig()
        assertTrue(engine.surroundTuneEnabled)
        assertEquals(cfg.surroundTune, engine.surroundTune)
    }

    @Test
    fun knobsShowTheirUnit() {
        assertEquals("55%", SurroundLabKnob.CENTER.display(0.55f))
        assertEquals("120 Hz", SurroundLabKnob.BASS.display(120f))
        assertEquals("12 ms", SurroundLabKnob.AMBIENCE.display(12f))
    }

    // ── Orbit styles ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun orbitPhaseAdvancesAndWraps() {
        assertEquals(0.75f, advanceSurroundOrbitPhase(0.5f, 1f, 4f), 1e-4f)
        assertEquals(0.4f, advanceSurroundOrbitPhase(0.9f, 2f, 4f), 1e-4f)
        // A silly period is clamped to the allowed range (3 s here).
        assertEquals(1f / 3f, advanceSurroundOrbitPhase(0f, 1f, 0.001f), 1e-4f)
        assertEquals(0f, advanceSurroundOrbitPhase(Float.NaN, 0f, 8f), 0f)
        assertEquals(0.25f, advanceSurroundOrbitPhase(0.25f, -5f, 8f), 1e-6f)
    }

    @Test
    fun circleGoesOnceAround() {
        val (angle, distance) = surroundOrbitPoint(SurroundOrbitPattern.CIRCLE, 0.25f, 0.8f)
        assertEquals(90f, angle, 1e-3f)
        assertEquals(0.8f, distance, 1e-6f)
    }

    @Test
    fun swayStaysOnTheFrontStage() {
        var p = 0f
        while (p <= 1f) {
            val (angle, distance) = surroundOrbitPoint(SurroundOrbitPattern.SWAY, p, 0.8f)
            assertTrue(surroundAngleGap(angle, 0f) <= 50.01f)
            assertEquals(0.8f, distance, 1e-6f)
            p += 0.05f
        }
    }

    @Test
    fun figureEightVisitsFrontThenBackAndCrossesAtTheListener() {
        val front = surroundOrbitPoint(SurroundOrbitPattern.FIGURE_EIGHT, 0.25f, 0.8f)
        assertTrue(surroundAngleGap(front.first, 0f) < 0.5f)
        assertEquals(0.8f, front.second, 0.01f)
        val back = surroundOrbitPoint(SurroundOrbitPattern.FIGURE_EIGHT, 0.75f, 0.8f)
        assertTrue(surroundAngleGap(back.first, 180f) < 0.5f)
        val cross = surroundOrbitPoint(SurroundOrbitPattern.FIGURE_EIGHT, 0f, 0.8f)
        assertEquals(SURROUND_POSITION_MIN_DISTANCE, cross.second, 1e-6f)
    }

    @Test
    fun spiralBreathesBetweenCloseAndTheChosenDistance() {
        val close = surroundOrbitPoint(SurroundOrbitPattern.SPIRAL, 0f, 0.8f)
        assertEquals(SURROUND_POSITION_MIN_DISTANCE, close.second, 1e-4f)
        val far = surroundOrbitPoint(SurroundOrbitPattern.SPIRAL, 1f / 6f, 0.8f)
        assertEquals(0.8f, far.second, 0.01f)
        assertEquals(60f, far.first, 0.5f)
    }

    @Test
    fun everyPatternStaysInsideTheAllowedRange() {
        for (pattern in SurroundOrbitPattern.entries) {
            for (base in listOf(0.15f, 0.5f, 1f)) {
                for (i in -20..220) {
                    val (angle, distance) = surroundOrbitPoint(pattern, i / 200f, base)
                    assertFalse(angle.isNaN() || distance.isNaN())
                    assertTrue("$pattern angle $angle", angle in 0f..360f)
                    assertTrue("$pattern distance $distance", distance in SURROUND_POSITION_MIN_DISTANCE..SURROUND_POSITION_MAX_DISTANCE)
                }
            }
        }
    }

    // ── Gyro follow ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun shortestTurnGoesTheShortWay() {
        assertEquals(20f, shortestAngleDelta(350f, 10f), 1e-4f)
        assertEquals(-20f, shortestAngleDelta(10f, 350f), 1e-4f)
        assertEquals(-90f, shortestAngleDelta(0f, 270f), 1e-4f)
        assertEquals(0f, shortestAngleDelta(123f, 123f), 1e-6f)
    }

    @Test
    fun smoothingCrossesNorthWithoutSpinningTheLongWay() {
        assertEquals(0f, smoothSurroundAngle(350f, 10f, 0.5f), 1e-3f)
        assertEquals(10f, smoothSurroundAngle(350f, 10f, 1f), 1e-3f)
        assertEquals(350f, smoothSurroundAngle(350f, 10f, 0f), 1e-3f)
    }

    @Test
    fun turningRightMovesTheSoundToTheLeft() {
        assertEquals(270f, surroundGyroAngle(0f, 90f, 0f), 1e-3f)
        assertEquals(20f, surroundGyroAngle(0f, 350f, 10f), 1e-3f)
        assertEquals(45f, surroundGyroAngle(45f, 200f, 200f), 1e-3f)
    }

    @Test
    fun headingFromTheRotationMatrix() {
        // Phone upright, back facing east -> 90.
        assertEquals(90f, surroundPhoneHeadingDeg(floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f))!!, 1e-3f)
        // Phone flat, top edge to the north -> 0 (identity matrix).
        assertEquals(0f, surroundPhoneHeadingDeg(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))!!, 1e-3f)
        // Phone flat, top edge to the east -> 90.
        assertEquals(90f, surroundPhoneHeadingDeg(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))!!, 1e-3f)
    }

    @Test
    fun unusableRotationMatrixGivesNoHeading() {
        assertNull(surroundPhoneHeadingDeg(FloatArray(3)))
        assertNull(surroundPhoneHeadingDeg(FloatArray(9) { Float.NaN }))
        assertNotNull(surroundPhoneHeadingDeg(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
    }
}
