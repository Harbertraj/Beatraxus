package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceTrackerTest {
    private val ms = 1_000_000L

    /** Render times of a [fps] source on a panel running at [panelHz]: every release time snapped to a vsync. */
    private fun snapped(fps: Double, panelHz: Double, frames: Int, startNs: Long = 5_000_000_000L): List<Long> {
        val vsync = 1e9 / panelHz
        return List(frames) { i ->
            val ideal = startNs + i * 1e9 / fps
            (Math.round(ideal / vsync) * vsync).toLong()
        }
    }

    private fun steps(ts: List<Long>) = ts.zipWithNext { a, b -> b - a }

    @Test fun pulldownJitterIsRemoved() {
        val raw = snapped(24.0, 60.0, 120)
        val rawSteps = steps(raw).drop(10)
        assertTrue("test input really is jittery", rawSteps.max() - rawSteps.min() > 12 * ms)

        val t = CadenceTracker()
        val out = raw.map { t.onFrame(it) }
        val s = steps(out).drop(10)
        val ideal = 1e9 / 24.0
        assertTrue("every step within 2 ms of 41.67 ms, was ${s.min()}..${s.max()}",
            s.all { abs(it - ideal) < 2 * ms })
        assertTrue("jitter cut by at least 5x", (s.max() - s.min()) * 5 < (rawSteps.max() - rawSteps.min()))
    }

    @Test fun outputStaysCloseToTheRealTimes() {
        val raw = snapped(24.0, 60.0, 200)
        val t = CadenceTracker()
        val out = raw.map { t.onFrame(it) }
        // never more than half a frame from the real time: no drift against the audio clock
        assertTrue(raw.zip(out).all { (r, o) -> abs(r - o) <= 21 * ms })
        // and no systematic offset
        val meanDev = raw.zip(out).drop(30).map { (r, o) -> (o - r).toDouble() }.average()
        assertTrue("mean deviation $meanDev ns", abs(meanDev) < 2 * ms)
    }

    @Test fun cleanCadenceIsLeftAlone() {
        val raw = List(80) { 1_000_000_000L + it * 8_333_333L }   // 120 fps on a 120 Hz panel
        val t = CadenceTracker()
        val out = raw.map { t.onFrame(it) }
        assertTrue(raw.zip(out).all { (r, o) -> abs(r - o) < 100_000 })
        assertEquals(8_333_333.0, t.intervalNs.toDouble(), 20_000.0)
    }

    @Test fun aDroppedFrameKeepsTheLine() {
        val ideal = 1e9 / 30.0
        val raw = (0 until 90).filter { it != 40 }.map { 2_000_000_000L + (it * ideal).toLong() }
        val t = CadenceTracker()
        val out = raw.map { t.onFrame(it) }
        // the frame after the gap is still on the 30 fps line, not pulled towards it
        val i = 40   // raw index of source frame 41
        assertTrue(abs(out[i] - raw[i]) < 2 * ms)
        assertEquals(ideal, t.intervalNs.toDouble(), 0.3 * ms)
    }

    @Test fun seekRestartsTheFit() {
        val t = CadenceTracker()
        snapped(24.0, 60.0, 60).forEach { t.onFrame(it) }
        val after = 90_000_000_000L
        val first = t.onFrame(after)
        assertEquals("first frame after a jump is passed through", after, first)
        assertEquals(0L, t.intervalNs)
        assertEquals(after + 41 * ms, t.onFrame(after + 41 * ms))   // still warming up: raw
    }

    @Test fun backwardsTimeRestarts() {
        val t = CadenceTracker()
        snapped(24.0, 60.0, 30).forEach { t.onFrame(it) }
        assertEquals(1_000_000_000L, t.onFrame(1_000_000_000L))
    }

    @Test fun longGapRestarts() {
        val t = CadenceTracker()
        val base = 3_000_000_000L
        (0 until 30).forEach { t.onFrame(base + it * 33 * ms) }
        val last = base + 29 * 33 * ms
        val after = last + 300 * ms     // a 300 ms pause
        assertEquals(after, t.onFrame(after))
    }

    @Test fun variableFrameRateJumpIsPassedThrough() {
        val t = CadenceTracker()
        val base = 1_000_000_000L
        (0 until 40).forEach { t.onFrame(base + it * 40 * ms) }
        // the next frame is 15 ms early: genuinely different timing, more than a quarter interval from the line
        val raw = base + 40 * 40 * ms - 25 * ms
        val out = t.onFrame(raw)
        assertTrue("early frame kept near its real time (${out - raw} ns)", abs(out - raw) <= 20 * ms)
    }

    @Test fun timeNeverRunsBackwards() {
        val t = CadenceTracker()
        var last = Long.MIN_VALUE
        snapped(24.0, 60.0, 300).forEach {
            val o = t.onFrame(it)
            assertTrue(o > last)
            last = o
        }
    }

    @Test fun halfSpeedPlaybackIsFollowed() {
        val t = CadenceTracker()
        val base = 1_000_000_000L
        val step = 83_333_333L       // 24 fps film at 0.5x
        val out = (0 until 80).map { t.onFrame(base + it * step) }
        val s = steps(out).drop(40)
        assertTrue(s.all { abs(it - step) < 2 * ms })
    }
}
