package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PresentationClockTest {
    private val ms = 1_000_000L

    @Test fun holdUsesTheSmallestRecentLead() {
        val h = HoldController()
        listOf(40L, 25L, 33L, 48L).forEach { h.onFrame(it * ms) }
        assertEquals(25 * ms, h.minLeadNs())
    }

    @Test fun oldLeadsFallOutOfTheWindow() {
        val h = HoldController(window = 4)
        h.onFrame(5 * ms)
        repeat(4) { h.onFrame(30 * ms) }
        assertEquals(30 * ms, h.minLeadNs())
    }

    @Test fun firstUpdateSnapsAndLaterOnesSlew() {
        val h = HoldController()
        val vsync = 8_333_333L
        h.onFrame(30 * ms)
        val first = h.update(41_666_667L, vsync)
        h.onFrame(5 * ms)                       // one very late frame: target hold jumps by 25 ms
        val second = h.update(41_666_667L, vsync)
        assertTrue("must move up", second > first)
        assertTrue("slew <= vsync/16", second - first <= vsync / HoldController.SLEW_UP_DIV)
    }

    @Test fun holdConvergesToTarget() {
        val h = HoldController()
        val vsync = 8_333_333L
        h.onFrame(30 * ms); h.update(41_666_667L, vsync)
        repeat(HoldController.WINDOW) { h.onFrame(20 * ms) }
        var v = 0L
        repeat(2000) { v = h.update(41_666_667L, vsync) }
        val target = com.beatraxus.app.motionboost.MotionBoostTiming.holdNs(41_666_667L, 20 * ms, vsync)
        assertEquals(target, v)
    }

    @Test fun resetSnapsAgain() {
        val h = HoldController()
        h.onFrame(30 * ms); h.update(41_666_667L, 8_333_333L)
        h.reset()
        h.onFrame(10 * ms)
        val v = h.update(41_666_667L, 8_333_333L)
        assertEquals(com.beatraxus.app.motionboost.MotionBoostTiming.holdNs(41_666_667L, 10 * ms, 8_333_333L), v)
    }

    private fun pick(ts: LongArray, t: Long) = FrameSelection.pick(ts.size, { ts[it] }, t)

    @Test fun picksBracketingPair() {
        val ts = longArrayOf(0, 40 * ms, 80 * ms, 120 * ms)
        val p = pick(ts, 50 * ms)
        assertEquals(1, p.a); assertEquals(2, p.b)
        assertEquals(0.25f, p.alpha, 1e-4f)
    }

    @Test fun earlyFramesWaitInTheQueue() {
        // newest frame (index 2) has already arrived but content time is still inside pair 0-1
        val ts = longArrayOf(0, 40 * ms, 80 * ms)
        val p = pick(ts, 20 * ms)
        assertEquals(0, p.a); assertEquals(1, p.b)
        assertEquals(0.5f, p.alpha, 1e-4f)
    }

    @Test fun beyondNewestShowsNewestAndBeforeOldestShowsOldest() {
        val ts = longArrayOf(10 * ms, 50 * ms)
        assertEquals(FrameSelection.Pick(1, 1, 1f), pick(ts, 500 * ms))
        assertEquals(FrameSelection.Pick(0, 0, 0f), pick(ts, 0))
        assertEquals(FrameSelection.Pick(0, 0, 1f), pick(longArrayOf(7), 7))
    }

    @Test fun contentTimeIsContinuousAcrossPairBoundaries() {
        val ts = longArrayOf(0, 41 * ms, 83 * ms, 125 * ms)
        var prev = -1.0
        var t = 1L
        while (t < 124 * ms) {
            val p = pick(ts, t)
            val content = ts[p.a] + p.alpha * (ts[p.b] - ts[p.a])
            if (prev >= 0) assertTrue("content time must advance smoothly", abs(content - t) < 100.0)   // ns; alpha is a Float
            prev = content
            t += 3 * ms
        }
    }

    @Test fun gapDetection() {
        assertFalse(FramePacing.isGap(42 * ms, 41 * ms))
        assertFalse(FramePacing.isGap(70 * ms, 41 * ms))      // a dropped frame is still interpolated
        assertTrue(FramePacing.isGap(300 * ms, 41 * ms))      // a short pause is not
        assertFalse(FramePacing.isGap(300 * ms, 0))           // unknown interval: never a gap
        assertFalse(FramePacing.isGap(60 * ms, 10 * ms))      // below the absolute minimum
    }
}
