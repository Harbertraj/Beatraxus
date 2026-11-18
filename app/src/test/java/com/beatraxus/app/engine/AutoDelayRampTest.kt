package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDelayRampTest {
    private val ms = 1_000_000L

    @Test fun firstCallSnapsBecauseNothingWasShownYet() {
        val r = AutoDelayRamp()
        assertEquals(80.0, r.update(80, 5_000 * ms), 1e-9)
    }

    @Test fun followsATargetNoFasterThanTwoPercent() {
        val r = AutoDelayRamp()
        r.update(0, 1_000 * ms)
        var t = 1_000L
        var prev = 0.0
        repeat(400) {
            t += 10
            val v = r.update(80, t * ms)
            assertTrue("step ${v - prev} ms in 10 ms", v - prev <= 10 * AutoDelayRamp.MAX_SLEW + 1e-9)
            prev = v
        }
        // 80 ms at 2 % takes 4 s: after 4 s of 10 ms calls it has arrived, and it does not overshoot.
        assertEquals(80.0, prev, 1e-6)
    }

    @Test fun rampsBackDownToZeroWhenTheBoosterStops() {
        val r = AutoDelayRamp()
        r.update(60, 1_000 * ms)
        var t = 1_000L
        repeat(400) { t += 10; r.update(0, t * ms) }
        assertEquals(0.0, r.valueMs, 1e-6)
    }

    @Test fun aLongStallDoesNotCauseAJump() {
        val r = AutoDelayRamp()
        r.update(0, 1_000 * ms)
        // 30 s pause: counted as one 250 ms step at most, so 5 ms at 2 %.
        val v = r.update(100, 31_000 * ms)
        assertEquals(250.0 * AutoDelayRamp.MAX_SLEW, v, 1e-9)
    }

    @Test fun targetIsClamped() {
        val r = AutoDelayRamp()
        assertEquals(AutoDelayRamp.MAX_MS.toDouble(), r.update(9_999, 1_000 * ms), 1e-9)
        val r2 = AutoDelayRamp()
        assertEquals(0.0, r2.update(-50, 1_000 * ms), 1e-9)
    }

    @Test fun tuningClampsAndExposesTheTotalShift() {
        val t = VideoPlaybackTuning(audioDelayMs = 100)
        t.autoDelayTargetMs = 999
        assertEquals(AutoDelayRamp.MAX_MS, t.autoDelayTargetMs)
        t.appliedAutoDelayMs = 70
        assertEquals(170, t.clockShiftMs)
    }
}
