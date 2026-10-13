package com.beatraxus.app.motionboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBoostTimingTest {

    private val ms = 1_000_000L

    @Test
    fun alphaIsLinearAndClamped() {
        assertEquals(0f, MotionBoostTiming.alpha(0, 100, 200), 0f)
        assertEquals(0f, MotionBoostTiming.alpha(100, 100, 200), 0f)
        assertEquals(0.5f, MotionBoostTiming.alpha(150, 100, 200), 1e-6f)
        assertEquals(1f, MotionBoostTiming.alpha(900, 100, 200), 0f)
        assertEquals(1f, MotionBoostTiming.alpha(150, 200, 200), 0f)
    }

    @Test
    fun holdNeverGoesNegativeAndIsCapped() {
        assertEquals(0L, MotionBoostTiming.holdNs(16 * ms, 100 * ms, 8 * ms))
        assertEquals(150 * ms, MotionBoostTiming.holdNs(1_000 * ms, 0, 8 * ms))
        // 24 fps source, frame handed over 30 ms early, 120 Hz panel
        val hold = MotionBoostTiming.holdNs(41_666_667L, 30 * ms, 8_333_333L)
        assertTrue(hold in 20 * ms..30 * ms)
    }

    @Test
    fun bypassWhenSourceAlreadyAtTarget() {
        assertTrue(MotionBoostTiming.shouldBypass(60f, 60))
        assertTrue(MotionBoostTiming.shouldBypass(59.94f, 60))
        assertTrue(MotionBoostTiming.shouldBypass(0f, 60))
        assertFalse(MotionBoostTiming.shouldBypass(24f, 60))
        assertFalse(MotionBoostTiming.shouldBypass(30f, 45))
    }

    @Test
    fun discontinuityDetection() {
        assertTrue(MotionBoostTiming.isContinuous(1_000 * ms, 1_042 * ms))
        assertFalse(MotionBoostTiming.isContinuous(1_000 * ms, 1_000 * ms))
        assertFalse(MotionBoostTiming.isContinuous(1_000 * ms, 900 * ms))
        assertFalse(MotionBoostTiming.isContinuous(1_000 * ms, 2_500 * ms))
    }

    @Test
    fun refreshRateSupport() {
        val panel60 = listOf(60f)
        val panel90 = listOf(60f, 90f)
        val panel120 = listOf(60f, 90f, 120f)
        assertTrue(MotionBoostTiming.isRateSupported(panel60, 60))
        assertFalse(MotionBoostTiming.isRateSupported(panel60, 45))
        assertFalse(MotionBoostTiming.isRateSupported(panel60, 120))
        assertTrue(MotionBoostTiming.isRateSupported(panel90, 45))
        assertTrue(MotionBoostTiming.isRateSupported(panel90, 90))
        assertFalse(MotionBoostTiming.isRateSupported(panel90, 120))
        assertTrue(MotionBoostTiming.isRateSupported(panel120, 120))
        assertTrue(MotionBoostTiming.isRateSupported(listOf(119.99f), 120))
    }

    @Test
    fun modeLookup() {
        assertEquals(MotionBoostMode.FPS_90, MotionBoostMode.fromName("FPS_90"))
        assertEquals(MotionBoostMode.ORIGINAL, MotionBoostMode.fromName("nope"))
        assertEquals(MotionBoostMode.ORIGINAL, MotionBoostMode.fromName(null))
        assertFalse(MotionBoostMode.ORIGINAL.isEnabled)
        assertTrue(MotionBoostMode.FPS_120.isEnabled)
    }

    @Test
    fun ninetyFpsOn120HzIsNotRoundedDownTo60() {
        val vsync = 8_333_333L
        val interval = (1_000_000_000.0 / 90).toLong()
        var due = 0L
        var drawn = 0
        var v = 1_000_000_000L
        val end = v + 2_000_000_000L
        while (v < end) {
            if (MotionBoostTiming.isDue(v, due, vsync)) {
                drawn++
                due = MotionBoostTiming.nextDue(due, v, interval)
            }
            v += vsync
        }
        assertTrue("drawn=$drawn", drawn in 178..182) // ~90 per second, not ~120 / 60
    }

    @Test
    fun hundredTwentyFpsOn120HzDrawsEveryVsync() {
        val vsync = 8_333_333L
        var due = 0L
        var drawn = 0
        var v = 1_000_000_000L
        repeat(240) {
            if (MotionBoostTiming.isDue(v, due, vsync)) {
                drawn++
                due = MotionBoostTiming.nextDue(due, v, vsync)
            }
            v += vsync
        }
        assertEquals(240, drawn)
    }

    @Test
    fun picksRefreshRateForTarget() {
        val modes = listOf(60f, 90f, 120f)
        assertEquals(90f, MotionBoostTiming.pickRefreshRate(modes, 90))
        assertEquals(120f, MotionBoostTiming.pickRefreshRate(modes, 120))
        assertEquals(60f, MotionBoostTiming.pickRefreshRate(modes, 60))
        assertEquals(90f, MotionBoostTiming.pickRefreshRate(modes, 45))
        assertEquals(120f, MotionBoostTiming.pickRefreshRate(listOf(60f, 120f), 90))
        assertEquals(120f, MotionBoostTiming.pickRefreshRate(listOf(60f, 120f), 144))
        assertEquals(null, MotionBoostTiming.pickRefreshRate(emptyList(), 90))
    }
}
