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

    @Test
    fun windowRefreshRateKeepsPlayerUiFast() {
        val modes = listOf(30f, 60f, 90f, 120f)
        // The reported bug: a 30 FPS target used to pin the whole window (controls, sheets) to 30 Hz.
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(modes, 30, uiHz = 120f))
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(listOf(60f, 120f), 30, uiHz = 120f))
        // 60 used to be pinned to exactly 60 Hz; 120 is an equally even multiple and keeps the UI smooth.
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(modes, 60, uiHz = 120f))
        // 45 FPS needs 90 Hz (nothing else is a multiple), 90 stays 90 on a 90/120 panel.
        assertEquals(90f, MotionBoostTiming.pickWindowRefreshRate(modes, 45, uiHz = 120f))
        assertEquals(90f, MotionBoostTiming.pickWindowRefreshRate(listOf(60f, 90f, 120f), 90, uiHz = 120f))
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(listOf(60f, 90f, 120f), 120, uiHz = 120f))
    }

    @Test
    fun windowRefreshRateFollowsAppSettingAndFallsBack() {
        val modes = listOf(60f, 90f, 120f)
        // The user pinned the app to 60 Hz: 30 FPS then runs on 60 Hz, not above what they asked for.
        assertEquals(60f, MotionBoostTiming.pickWindowRefreshRate(modes, 30, uiHz = 60f))
        // Closest multiple wins; on a tie the higher one (smoother).
        assertEquals(90f, MotionBoostTiming.pickWindowRefreshRate(modes, 30, uiHz = 90f))
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(listOf(60f, 120f), 30, uiHz = 90f))
        // No multiple at all (45 on 60/120, 144 on 120): stay on the app's rate instead of dropping the UI to the target.
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(listOf(60f, 120f), 45, uiHz = 120f))
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(listOf(60f, 120f), 144, uiHz = 120f))
        // Unknown app rate: as fast as possible. Nothing to choose: null.
        assertEquals(120f, MotionBoostTiming.pickWindowRefreshRate(modes, 30, uiHz = null))
        assertEquals(null, MotionBoostTiming.pickWindowRefreshRate(emptyList(), 30, uiHz = 120f))
        assertEquals(null, MotionBoostTiming.pickWindowRefreshRate(modes, 0, uiHz = 120f))
    }

    @Test
    fun stepNsRoundsToVsyncPeriodMultiple() {
        assertEquals(2 * 8_333_333L, MotionBoostTiming.stepNs(16_666_667L, 8_333_333L))
        assertEquals(11_111_111L, MotionBoostTiming.stepNs(11_111_111L, 8_333_333L))
    }

    @Test
    fun nextDueStaysAnchoredOver100kFramesWithRealVsync() {
        val vsync = 8_333_300L
        val interval = 16_666_666L
        val step = MotionBoostTiming.stepNs(interval, vsync)
        var due = 0L
        var v = 0L
        var drawn = 0
        repeat(100_000) {
            if (MotionBoostTiming.isDue(v, due, vsync)) {
                drawn++
                due = MotionBoostTiming.nextDue(due, v, step)
            }
            v += vsync
        }
        assertEquals(50_000, drawn)
    }

    @Test
    fun medianIgnoresSingleOutlier() {
        val ring = LongArray(9)
        var count = 0
        var head = 0
        val scratch = LongArray(9)

        fun addPeriod(p: Long): Long {
            if (p in 3_000_000L..40_000_000L) {
                ring[head] = p
                head = (head + 1) % 9
                if (count < 9) count++
                System.arraycopy(ring, 0, scratch, 0, count)
                scratch.sort(0, count)
                return scratch[count / 2]
            }
            return 8_333_333L
        }

        repeat(8) { addPeriod(8_333_333L) }
        val median = addPeriod(16_666_667L)
        assertEquals(8_333_333L, median)
    }
}

