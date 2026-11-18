package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePtsTest {
    private val vsync = 16_666_667L
    private fun snap(t: Long) = (t + vsync / 2) / vsync * vsync

    @Test fun lookupFindsTheFrameByItsRenderTime() {
        FramePts.clear()
        FramePts.record(1_000_000_000L, 40_000_000L)
        FramePts.record(1_050_000_000L, 80_000_000L)
        assertEquals(80_000_000L, FramePts.lookupNs(1_050_000_000L))
        assertEquals(40_000_000L, FramePts.lookupNs(1_000_000_000L + 1_000_000L))
        assertEquals(-1L, FramePts.lookupNs(1_020_000_000L))
        FramePts.clear()
        assertEquals(-1L, FramePts.lookupNs(1_000_000_000L))
    }

    @Test fun ringKeepsOnlyTheNewestFrames() {
        FramePts.clear()
        for (i in 0 until 100) FramePts.record(i * 50_000_000L, i * 40_000_000L)
        assertEquals(-1L, FramePts.lookupNs(0L))
        assertEquals(99 * 40_000_000L, FramePts.lookupNs(99 * 50_000_000L))
    }

    /** 24 fps film on 60 Hz: the raw stamps step 33 / 50 ms, the container times are an exact 41.67 ms (the offset filter leaves about 1 ms, raw has 17). */
    @Test fun containerTimesGiveExactlyEvenSpacing() {
        val r = TimestampRegularizer()
        val interval = 1e9 / 24.0
        val out = ArrayList<Long>()
        for (i in 0 until 240) {
            val pts = (i * interval).toLong()
            val raw = snap(2_000_000_000L + pts + 25_000_000L)
            out.add(r.next(raw, vsync, pts))
        }
        assertTrue(r.ptsLocked)
        val steps = out.zipWithNext { a, b -> b - a }.drop(20)
        assertTrue("jitter ${steps.max() - steps.min()}", steps.max() - steps.min() < 1_500_000L)
        assertTrue(abs(steps.average() - interval) < 10_000.0)
    }

    @Test fun staysCloseToTheRawClock() {
        val r = TimestampRegularizer()
        val interval = 1e9 / 24.0
        for (i in 0 until 240) {
            val pts = (i * interval).toLong()
            val raw = snap(2_000_000_000L + pts + 25_000_000L)
            val out = r.next(raw, vsync, pts)
            if (i > 20) assertTrue("frame $i off by ${out - raw}", abs(out - raw) <= vsync)
        }
    }

    @Test fun playbackSpeedOtherThanOneNeverLocks() {
        val r = TimestampRegularizer()
        val interval = 1e9 / 24.0
        for (i in 0 until 200) {
            val pts = (i * interval).toLong()
            val raw = snap(2_000_000_000L + (pts / 2))   // 2x speed: raw runs twice as fast as pts
            r.next(raw, vsync, pts)
        }
        assertTrue("must fall back to the statistical path", !r.ptsLocked)
    }

    @Test fun missingContainerTimeUsesTheStatisticalPath() {
        val a = TimestampRegularizer()
        val b = TimestampRegularizer()
        val interval = 1e9 / 25.0
        for (i in 0 until 100) {
            val raw = snap(1_000_000_000L + (i * interval).toLong())
            assertEquals(b.next(raw, vsync), a.next(raw, vsync, -1L))
        }
    }

    @Test fun seekRelocksAfterAFewFramesAndStaysIncreasing() {
        val r = TimestampRegularizer()
        val interval = 1e9 / 30.0
        var last = Long.MIN_VALUE
        var rawBase = 5_000_000_000L
        var ptsBase = 0L
        for (i in 0 until 300) {
            if (i == 150) { ptsBase = 600_000_000_000L; rawBase += 700_000_000L }   // seek far forward, with a pause
            val k = if (i < 150) i else i - 150
            val pts = ptsBase + (k * interval).toLong()
            val raw = snap(rawBase + (k * interval).toLong())
            val out = r.next(raw, vsync, pts)
            if (i < 150 || i >= 160) assertTrue("frame $i not increasing", out > last)
            last = out
        }
        assertTrue(r.ptsLocked)
    }
}
