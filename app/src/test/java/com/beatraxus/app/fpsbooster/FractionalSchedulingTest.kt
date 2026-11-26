package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Timestamp-based fractional frame placement: exact, evenly spaced, drift free (see [MultipleMode.outputTimeNs]). */
class FractionalSchedulingTest {
    private val ms = 1_000_000L

    /** Source timestamps of [frames] frames at [fps] (exact, from the index: no accumulated rounding). */
    private fun source(fps: Double, frames: Int, startNs: Long = 0L) =
        LongArray(frames) { startNs + Math.round(it * 1e9 / fps) }

    private fun count(frames: List<FramePacing.OutputFrame>) =
        frames.groupBy { it.pair }.mapValues { it.value.size }

    // ---- distribution between source frames ----

    @Test fun twentyFiveToSixtyHasTwoOrThreeOutputsPerSourceInterval() {
        val out = FramePacing.schedule(source(25.0, 251), 60)
        val per = count(out).filterKeys { it < 249 }.values.toSet()
        assertEquals(setOf(2, 3), per)
        // 12 outputs per 5 source frames, in the 2,3,2,3,2 order of an exact placement.
        assertEquals(listOf(3, 2, 3, 2, 2), (0 until 5).map { count(out)[it]!! })
    }

    @Test fun everyRequiredRatioDistributesOutputsWithoutAFixedCount() {
        for ((s, t) in listOf(25 to 60, 25 to 90, 25 to 120, 24 to 60, 24 to 90, 40 to 90, 50 to 120, 40 to 60)) {
            val out = FramePacing.schedule(source(s.toDouble(), s * 10 + 1), t)
            val per = count(out).filterKeys { it < s * 10 - 1 }.values
            val ratio = t.toDouble() / s
            // Every interval holds floor or ceil of the ratio, and the average is the ratio.
            assertTrue("$s -> $t", per.all { it == Math.floor(ratio).toInt() || it == Math.ceil(ratio).toInt() })
            assertEquals("$s -> $t", ratio, per.average(), 0.02)
            if (ratio != Math.floor(ratio)) assertTrue("$s -> $t must not force one count", per.toSet().size == 2)
        }
    }

    @Test fun wholeRatiosKeepOneCountPerInterval() {
        for ((s, t) in listOf(24 to 120, 30 to 60, 30 to 90, 30 to 120)) {
            val per = count(FramePacing.schedule(source(s.toDouble(), s * 5 + 1), t)).filterKeys { it < s * 5 - 1 }.values.toSet()
            assertEquals("$s -> $t", setOf(t / s), per)
        }
    }

    // ---- even cadence ----

    @Test fun outputTimestampsAreEvenlySpaced() {
        for (t in listOf(60, 90, 120)) {
            val out = FramePacing.schedule(source(25.0, 400), t)
            val step = 1e9 / t
            out.zipWithNext { a, b -> assertEquals(step, (b.timeNs - a.timeNs).toDouble(), 1.0) }   // 1 ns: integer rounding only
        }
    }

    @Test fun noAccumulatedDriftOverAnHour() {
        val anchor = 123_456_789L
        for (t in listOf(30, 45, 60, 90, 120)) {
            val k = 3600L * t
            val exact = anchor + 3_600_000_000_000L
            assertEquals("$t FPS", exact.toDouble(), MultipleMode.outputTimeNs(anchor, k, t).toDouble(), 1.0)
        }
        // The old way (adding the truncated step every frame) is over 100 microseconds off after the same hour at 60 FPS.
        val oldWay = anchor + 3600L * 60 * FramePacing.intervalNs(60)
        assertTrue(abs(oldWay - (anchor + 3_600_000_000_000L)) > 100_000L)
    }

    @Test fun playbackDurationIsPreserved() {
        val ts = source(25.0, 251)                                   // 10 s
        val out = FramePacing.schedule(ts, 120)
        val last = out.last().timeNs
        assertTrue(last < ts.last() && ts.last() - last <= 1e9 / 120 + 1)
        assertEquals(ts.first(), out.first().timeNs)                 // the first output IS the first source frame
        assertEquals(1200.0, out.size.toDouble(), 1.0)                              // 10 s at 120 FPS
    }

    // ---- source frames preserved ----

    @Test fun sourceFramesAreShownUntouchedWhenTheClocksMeet() {
        // 25 -> 60: an output coincides with a real frame every 5 source frames (every 12 outputs), alpha exactly 0.
        val out = FramePacing.schedule(source(25.0, 251), 60)
        val real = out.filter { abs(it.alpha) < 1e-4f }
        assertTrue(real.isNotEmpty())
        real.forEach { f ->
            val k = Math.round(f.timeNs.toDouble() * 25 / 1e9)
            assertEquals(f.timeNs.toDouble(), k * 40.0 * ms, 2.0)     // exactly on a source timestamp
        }
        assertEquals(0, out.first().pair); assertEquals(0f, out.first().alpha, 0f)
        // every other output is a synthesised, strictly-between position
        out.filter { it.alpha > 1e-4f }.forEach { assertTrue(it.alpha < 1f) }
    }

    @Test fun alphasFollowTheExactTimestampPositions() {
        // 25 -> 60: outputs sit at 0, 5/12 (0.41667), 10/12 (0.83333) source frames ...
        val out = FramePacing.schedule(source(25.0, 20), 60).take(4)
        assertEquals(0.0, out[0].alpha.toDouble(), 1e-6)
        assertEquals(0.41667, out[1].alpha.toDouble(), 1e-4); assertEquals(0, out[1].pair)
        assertEquals(0.83333, out[2].alpha.toDouble(), 1e-4); assertEquals(0, out[2].pair)
        assertEquals(0.25, out[3].alpha.toDouble(), 1e-4);    assertEquals(1, out[3].pair)
    }

    @Test fun nothingIsDuplicatedWithinAnInterval() {
        val out = FramePacing.schedule(source(25.0, 120), 90)
        out.groupBy { it.pair }.values.forEach { g ->
            assertEquals(g.size, g.map { it.alpha }.distinct().size)
            assertTrue(g.zipWithNext().all { (a, b) -> b.alpha > a.alpha })
        }
    }

    // ---- variable frame rate and discontinuities ----

    @Test fun variableFrameRateIsPlacedByItsOwnTimestamps() {
        // 24 FPS then 30 FPS: placement follows each real interval; output spacing never changes.
        val ts = source(24.0, 49) + source(30.0, 61, startNs = 2_000_000_000L + 33 * ms)
        val out = FramePacing.schedule(ts, 60)
        out.zipWithNext { a, b -> assertEquals(1e9 / 60, (b.timeNs - a.timeNs).toDouble(), 1.0) }
        out.forEach { f ->
            val a = ts[f.pair]; val b = ts[f.pair + 1]
            if (b - a < 1_000_000_000L) assertEquals(a + f.alpha.toDouble() * (b - a), f.timeNs.toDouble(), 100.0)   // alpha is a Float
        }
    }

    @Test fun seekAndRestartAreNotInterpolatedAcross() {
        val ts = source(25.0, 10) + source(25.0, 10, startNs = 60_000_000_000L)
        val out = FramePacing.schedule(ts, 60)
        val across = out.filter { it.timeNs in (ts[9] + 1) until ts[10] }
        assertTrue(across.isNotEmpty())
        assertTrue(across.all { it.alpha == 0f && !it.interpolated })        // the earlier real frame is held: nothing invented
        // a new schedule after the seek starts again on a real frame
        val after = FramePacing.schedule(source(25.0, 10, startNs = 60_000_000_000L), 60)
        assertEquals(0f, after.first().alpha, 0f)
    }

    @Test fun aRefreshRateChangeSimplyRebuildsTheScheduleAtTheNewTarget() {
        val ts = source(25.0, 100)
        val at60 = FramePacing.schedule(ts, 60); val at120 = FramePacing.schedule(ts, 120)
        assertEquals(at60.size * 2.0, at120.size.toDouble(), 2.0)
        assertFalse(at60.map { it.timeNs } == at120.map { it.timeNs })
    }
}
