package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceGovernorTest {

    @Test fun cleanTargetsUseAWholeNumberOfVsyncs() {
        assertEquals(1, CadenceMath.bestDivisor(120, 120f))
        assertEquals(2, CadenceMath.bestDivisor(60, 120f))
        assertEquals(4, CadenceMath.bestDivisor(30, 120f))
        assertEquals(2, CadenceMath.bestDivisor(45, 90f))
        assertEquals(1, CadenceMath.bestDivisor(90, 90f))
        assertEquals(2, CadenceMath.bestDivisor(60, 119.99f))
    }

    @Test fun targetsAboveThePanelRateUseEveryVsync() {
        assertEquals(1, CadenceMath.bestDivisor(120, 60f))
        assertEquals(1, CadenceMath.bestDivisor(90, 60f))
    }

    @Test fun unreachableTargetsPickTheClosestEvenRate() {
        // 45 on 120 Hz: 40 FPS (every 3rd vsync) is closer than 60 FPS (every 2nd).
        assertEquals(3, CadenceMath.bestDivisor(45, 120f))
        // 45 on 60 Hz: 30 (n=2) and 60 (n=1) are equally far; the lower, cheaper rate wins.
        assertEquals(2, CadenceMath.bestDivisor(45, 60f))
        // 90 on 120 Hz: 120 and 60 are equally far; the lower one wins.
        assertEquals(2, CadenceMath.bestDivisor(90, 120f))
    }

    @Test fun exactnessMatchesCleanness() {
        assertTrue(CadenceMath.isExact(60, 120f)); assertTrue(CadenceMath.isExact(45, 90f))
        assertFalse(CadenceMath.isExact(45, 120f)); assertFalse(CadenceMath.isExact(90, 120f))
    }

    @Test fun cleanMultiplePredicateAgreesWithTheGovernor() {
        for (hz in listOf(60f, 90f, 120f, 144f)) for (t in listOf(24, 30, 45, 60, 90, 120)) {
            assertEquals("hz=$hz target=$t", MotionBoostTiming.isCleanMultiple(hz, t), CadenceMath.isExact(t, hz) && hz >= t)
        }
    }

    @Test fun stepIsAWholeNumberOfVsyncPeriods() {
        val g = CadenceGovernor()
        val period = 8_333_333L // 120 Hz
        assertEquals(2 * period, g.stepNs(60, period))
        assertEquals(2, g.vsyncsPerFrame); assertTrue(g.exact)
        assertEquals(60f, g.outputFps, 0.2f)
        assertEquals(3 * period, g.stepNs(45, period))
        assertFalse(g.exact); assertEquals(40f, g.outputFps, 0.2f); assertEquals(40, g.effectiveTarget(45))
    }

    @Test fun noisyPeriodNeverFlipsATiedChoice() {
        val g = CadenceGovernor()
        val first = g.stepNs(90, 8_333_333L) / 8_333_333L
        var flips = 0
        var last = first
        // 90 on 120 Hz is a tie between 120 and 60 FPS; jitter in the measured period must not alternate them.
        for (i in 0 until 1000) {
            val period = 8_333_333L + (i % 7 - 3) * 6_000L
            val n = g.stepNs(90, period) / period
            if (n != last) { flips++; last = n }
        }
        assertEquals(0, flips)
    }

    @Test fun changingTheTargetOrTheDisplayRateReselects() {
        val g = CadenceGovernor()
        g.stepNs(45, 8_333_333L)                   // 120 Hz: n = 3 (40 FPS)
        assertEquals(3, g.vsyncsPerFrame)
        g.stepNs(45, 11_111_111L)                  // the panel moved to 90 Hz: n = 2 is exact now
        assertEquals(2, g.vsyncsPerFrame); assertTrue(g.exact)
        g.stepNs(60, 8_333_333L)
        assertEquals(2, g.vsyncsPerFrame)
    }

    @Test fun nextDueOnGridKeepsTheCadenceOverAMillionVsyncsWithAWrongMeasuredPeriod() {
        // True panel period 8_333_333 ns; the measured median is 3 us off. Anchoring to the vsync that showed the
        // frame keeps the spacing at exactly 2 true vsyncs; due-time accumulation would drift by 6 us per frame.
        val truePeriod = 8_333_333L
        val measured = truePeriod + 3_000L
        val g = CadenceGovernor()
        val step = g.stepNs(60, measured)
        var due = 0L
        var lastShown = -1L
        var maxSpacingError = 0L
        var v = 0L
        var shown = 0
        for (i in 0 until 1_000_000) {
            v += truePeriod
            if (MotionBoostTiming.isDue(v, due, measured)) {
                if (lastShown >= 0) maxSpacingError = maxOf(maxSpacingError, kotlin.math.abs(v - lastShown - 2 * truePeriod))
                lastShown = v
                due = MotionBoostTiming.nextDueOnGrid(v, step)
                shown++
            }
        }
        assertEquals(0L, maxSpacingError)
        assertEquals(500_000, shown)
    }

    @Test fun aLateFrameDelaysTheCadenceOnceInsteadOfCatchingUp() {
        val period = 8_333_333L
        val step = 2 * period
        var due = MotionBoostTiming.nextDueOnGrid(0L, step)
        // The frame due at vsync 2 is only drawn at vsync 3.
        assertTrue(MotionBoostTiming.isDue(3 * period, due, period))
        due = MotionBoostTiming.nextDueOnGrid(3 * period, step)
        assertEquals(5 * period, due)
        // Following frames are 2 vsyncs apart again, no short hold afterwards.
        assertFalse(MotionBoostTiming.isDue(4 * period, due, period))
        assertTrue(MotionBoostTiming.isDue(5 * period, due, period))
    }
}
