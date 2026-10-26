package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampRegularizerTest {
    private val vsync = 16_666_667L
    private fun snap(t: Long) = (t + vsync / 2) / vsync * vsync

    private fun run(fps: Double, n: Int): Pair<List<Long>, List<Long>> {
        val r = TimestampRegularizer()
        val interval = 1e9 / fps
        val raw = (0 until n).map { snap(1_000_000_000L + (it * interval).toLong()) }
        return raw to raw.map { r.next(it, vsync) }
    }

    private fun jitter(xs: List<Long>, from: Int): Long {
        val d = xs.zipWithNext { a, b -> b - a }.drop(from)
        return d.max() - d.min()
    }

    @Test fun jitterOf24And25FpsOn60HzIsRemoved() {
        for (fps in listOf(24.0, 25.0, 23.976)) {
            val (raw, out) = run(fps, 120)
            assertTrue("$fps raw jitter ${jitter(raw, 10)}", jitter(raw, 10) >= 15_000_000L)
            assertTrue("$fps regularised jitter ${jitter(out, 30)}", jitter(out, 30) < 4_000_000L)
        }
    }

    @Test fun staysCloseToRawClock() {
        val (raw, out) = run(24.0, 240)
        raw.zip(out).drop(10).forEach { (r, o) -> assertTrue(abs(r - o) <= vsync) }
    }

    @Test fun strictlyIncreasing() {
        val (_, out) = run(24.0, 200)
        assertTrue(out.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun seekResetsInsteadOfSmoothing() {
        val r = TimestampRegularizer()
        var t = 1_000_000_000L
        repeat(30) { r.next(t, vsync); t += 41_666_667L }
        val jump = t + 5_000_000_000L
        assertEquals(jump, r.next(jump, vsync))
        assertEquals(0L, r.intervalNs)
    }

    @Test fun droppedFrameResets() {
        val r = TimestampRegularizer()
        var t = 1_000_000_000L
        repeat(30) { r.next(t, vsync); t += 41_666_667L }
        t += 41_666_667L * 3          // three frames lost
        assertEquals(t, r.next(t, vsync))
    }

    @Test fun firstFramesPassThrough() {
        val r = TimestampRegularizer()
        assertEquals(123L, r.next(123L, vsync))
        assertEquals(41_666_790L, r.next(41_666_790L, vsync))
    }

    @Test fun intervalEstimateIsAccurate() {
        val (_, _) = run(24.0, 10)
        val r = TimestampRegularizer()
        val interval = 1e9 / 24.0
        (0 until 100).forEach { r.next(snap(1_000_000_000L + (it * interval).toLong()), vsync) }
        assertTrue(abs(r.intervalNs - interval) < 300_000)
    }
}
