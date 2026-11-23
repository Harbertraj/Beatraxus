package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VsyncPeriodEstimatorTest {
    private val p60 = 16_666_667L
    private val p90 = 11_111_111L
    private val p120 = 8_333_333L

    private fun feed(e: VsyncPeriodEstimator, gaps: List<Long>, hz: Float = 0f): Long {
        var p = e.periodNs
        for (g in gaps) p = e.onGap(g, hz)
        return p
    }

    /** [late] of every [of] callbacks arrive one vsync late: a gap of two periods. */
    private fun lateMix(period: Long, n: Int, late: Int, of: Int) =
        List(n) { if (it % of < late) 2 * period else period }

    @Test fun steadyPanelGivesItsOwnPeriod() {
        assertEquals(p90.toDouble(), feed(VsyncPeriodEstimator(), List(40) { p90 }).toDouble(), 1.0)
        assertEquals(p120.toDouble(), feed(VsyncPeriodEstimator(), List(40) { p120 }).toDouble(), 1.0)
    }

    @Test fun lateCallbacksDoNotMakeThePanelSlower() {
        // Two of every three callbacks late: the old median of 9 read this as 45 Hz (90 Hz panel) / 60 Hz (120 Hz panel).
        assertEquals(p90.toDouble(), feed(VsyncPeriodEstimator(), lateMix(p90, 90, 2, 3)).toDouble(), 1.0)
        assertEquals(p120.toDouble(), feed(VsyncPeriodEstimator(), lateMix(p120, 90, 2, 3)).toDouble(), 1.0)
    }

    @Test fun everyCallbackLateNeedsTheReportedPanelRate() {
        val allLate = List(60) { 2 * p90 }
        // Without a hint nothing tells a slow panel from a late presenter: the measurement is all there is.
        assertEquals((2 * p90).toDouble(), feed(VsyncPeriodEstimator(), allLate).toDouble(), 1.0)
        // With the rate the system reports, a whole multiple of it is lateness.
        assertEquals(p90.toDouble(), feed(VsyncPeriodEstimator(), allLate, hz = 90f).toDouble(), 2.0)
        assertEquals(p120.toDouble(), feed(VsyncPeriodEstimator(), List(60) { 3 * p120 }, hz = 120f).toDouble(), 2.0)
    }

    @Test fun aRealModeChangeIsFollowed() {
        val e = VsyncPeriodEstimator()
        feed(e, List(40) { p120 }, hz = 120f)
        assertEquals(p90.toDouble(), feed(e, List(40) { p90 }, hz = 90f).toDouble(), 1.0)
        // 120 -> 60 is exactly 2x, but the reported rate moved with it, so it is not mistaken for lateness.
        val f = VsyncPeriodEstimator()
        feed(f, List(40) { p120 }, hz = 120f)
        assertEquals(p60.toDouble(), feed(f, List(40) { p60 }, hz = 60f).toDouble(), 1.0)
    }

    @Test fun oneStrayTinyGapDoesNotDefineThePeriod() {
        val gaps = List(31) { p120 }.toMutableList().also { it.add(10, 3_500_000L) }
        assertEquals(p120.toDouble(), feed(VsyncPeriodEstimator(), gaps).toDouble(), 1.0)
    }

    @Test fun gapsOutsideTheUsableRangeAreIgnored() {
        val e = VsyncPeriodEstimator()
        assertEquals(VsyncPeriodEstimator.DEFAULT_PERIOD_NS, e.onGap(1_000_000L))
        assertEquals(VsyncPeriodEstimator.DEFAULT_PERIOD_NS, e.onGap(100_000_000L))
    }

    @Test fun resetKeepsTheLastEstimate() {
        val e = VsyncPeriodEstimator()
        feed(e, List(40) { p90 })
        e.reset()
        assertEquals(p90.toDouble(), e.periodNs.toDouble(), 1.0)
    }

    @Test fun targetStaysOnTheNativeRateWhileTheThreadIsLate() {
        // The reported bug: target 90 on a 90 Hz panel showed 45 FPS cadence; 120 on 120 showed 60.
        for ((period, target) in listOf(p90 to 90, p120 to 120)) {
            val e = VsyncPeriodEstimator()
            val g = CadenceGovernor()
            for (gap in lateMix(period, 120, 2, 3)) {
                val p = e.onGap(gap)
                g.stepNs(target, p)
                if (e.periodNs == p && g.vsyncsPerFrame > 0) {
                    assertEquals("target $target", 1, g.vsyncsPerFrame)
                }
            }
            assertEquals(target.toFloat(), g.outputFps, 0.5f)
            assertTrue(g.exact)
        }
    }
}
