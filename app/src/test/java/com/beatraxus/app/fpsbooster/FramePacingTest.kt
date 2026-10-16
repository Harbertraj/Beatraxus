package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingTest {
    private val ms = 1_000_000L
    private fun ts24(frames: Int) = LongArray(frames) { (it * 1_000_000_000.0 / 24).toLong() }

    @Test fun twentyFourToSixtyGivesSixtyFramesPerSecondOfWhichEightyPercentAreGenerated() {
        val out = FramePacing.schedule(ts24(25), 60)
        // the step is truncated to whole nanoseconds, so a 61st frame can land just inside the second
        assertTrue("size=${out.size}", out.size in 60..61)
        val generated = out.count { it.generated }
        // clocks only line up every 5th output frame (t = 0, 83.3 ms, ...): 12 real + 48 synthesised per second
        assertTrue("generated=$generated", generated in 47..49)
        assertEquals(FramePacing.generatedShare(24f, 60) * out.size, generated.toFloat(), 1.5f)
    }

    @Test fun twentyFourToSixtyUsesTheExpectedPhases() {
        val a = FramePacing.schedule(ts24(10), 60).take(6).map { it.alpha }
        // output times 0, 16.7, 33.3, 50, 66.7, 83.3 ms against source frames every 41.67 ms
        val expected = listOf(0f, 0.4f, 0.8f, 0.2f, 0.6f, 0f)
        // an output time a few ns before a source frame reads as alpha ~1 of the previous pair: same picture as alpha 0
        a.zip(expected).forEachIndexed { i, (got, want) ->
            val g = if (got > 0.99f) 0f else got
            assertEquals("frame $i", want, g, 0.01f)
        }
    }

    @Test fun twentyFourToThirtyUsesFivePhaseCycle() {
        val frames = FramePacing.schedule(ts24(10), 30).take(5)
        assertEquals(listOf(0f, 0.8f, 0.6f, 0.4f, 0.2f), frames.map { Math.round(it.alpha * 10) / 10f })
        assertEquals(listOf(0, 0, 1, 2, 3), frames.map { it.pair })
    }

    @Test fun outputGapsStayEvenForSteadySources() {
        val out = FramePacing.schedule(ts24(25), 60)
        val step = FramePacing.intervalNs(60)
        assertTrue(FramePacing.maxGapNs(out) <= step + 1)
    }

    @Test fun nothingIsInventedAcrossASeek() {
        // 3 frames, then a 5 s jump
        val ts = longArrayOf(0, 41_666_667, 83_333_333, 5_000 * ms, 5_041 * ms)
        val out = FramePacing.schedule(ts, 60)
        val inGap = out.filter { it.timeNs in 84 * ms until 4_999 * ms }
        assertTrue(inGap.isNotEmpty())
        assertTrue(inGap.all { !it.interpolated && it.alpha == 0f && !it.generated })
    }

    @Test fun noInterpolationNeededWhenSourceAlreadyFast() {
        assertEquals(0f, FramePacing.generatedShare(60f, 60), 0f)
        assertEquals(0f, FramePacing.generatedShare(60f, 30), 0f)
        assertEquals(0f, FramePacing.generatedShare(0f, 60), 0f)
        assertEquals(0.5f, FramePacing.generatedShare(30f, 60), 1e-6f)
        assertEquals(0.5f, FramePacing.generatedShare(24f, 48), 1e-6f)
        assertEquals(0.8f, FramePacing.generatedShare(24f, 60), 1e-6f)
        assertEquals(0.8f, FramePacing.generatedShare(24f, 30), 1e-6f)
        assertEquals(11f / 12f, FramePacing.generatedShare(25f, 60), 1e-6f)
        assertEquals(1f, FramePacing.generatedShare(23.976f, 60), 0f)
    }

    @Test fun tooFewFramesGivesEmptySchedule() {
        assertTrue(FramePacing.schedule(longArrayOf(0), 60).isEmpty())
        assertTrue(FramePacing.schedule(longArrayOf(), 60).isEmpty())
    }

    @Test fun missedOutputsNeverNegative() {
        assertEquals(0, FramePacing.missedOutputs(60f, 1_000_000_000L, 60))
        assertEquals(0, FramePacing.missedOutputs(60f, 1_000_000_000L, 75))
        assertEquals(10, FramePacing.missedOutputs(60f, 1_000_000_000L, 50))
    }

    @Test fun achievableRateIsLimitedByPanel() {
        assertEquals(60, FramePacing.achievableFps(120, listOf(60f)))
        assertEquals(90, FramePacing.achievableFps(90, listOf(60f, 90f, 120f)))
        assertEquals(60, FramePacing.achievableFps(60, emptyList()))
    }

    @Test fun contentTimeIsHeldBack() {
        assertEquals(1_000L + 8 - 30, FramePacing.contentTimeNs(1_000, 8, 30))
        assertFalse(FramePacing.contentTimeNs(1_000, 8, 30) > 1_000)
    }
}
