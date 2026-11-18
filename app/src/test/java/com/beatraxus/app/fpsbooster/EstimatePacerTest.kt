package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatePacerTest {
    private val v = 8.33f

    @Test fun fullSliceWhilePresentingIsHealthy() {
        val p = EstimatePacer()
        repeat(50) { p.onPresented(false, 2f, v); assertEquals(4, p.slice(4)) }
    }

    @Test fun lateFrameHalvesTheSliceAndRecoversAfterCleanVsyncs() {
        val p = EstimatePacer()
        p.onPresented(true, 2f, v)
        assertEquals(2, p.slice(4))
        repeat(EstimatePacer.RECOVER_AFTER) { p.onPresented(false, 2f, v) }
        assertEquals(3, p.slice(4))
        repeat(EstimatePacer.RECOVER_AFTER) { p.onPresented(false, 2f, v) }
        assertEquals(4, p.slice(4))
    }

    @Test fun slowDrawCountsAsPressureEvenWhenOnTime() {
        val p = EstimatePacer()
        p.onPresented(false, v * 0.9f, v)
        assertTrue(p.share < 1.0)
    }

    @Test fun neverBelowAQuarterAndNeverTwoSkipsInARow() {
        val p = EstimatePacer()
        repeat(20) { p.onPresented(true, 9f, v) }
        assertEquals(EstimatePacer.MIN_SHARE, p.share, 1e-9)
        var run = 0
        var maxRun = 0
        var total = 0
        repeat(100) {
            val s = p.slice(1)
            total += s
            if (s == 0) { run++; maxRun = maxOf(maxRun, run) } else run = 0
        }
        assertEquals(1, maxRun)
        assertEquals(50, total)   // an estimate under full pressure still advances every other vsync
    }

    @Test fun nothingPlannedMeansNothingSubmitted() {
        assertEquals(0, EstimatePacer().slice(0))
    }

    @Test fun resetGivesTheFullSliceBack() {
        val p = EstimatePacer()
        repeat(5) { p.onPresented(true, 9f, v) }
        p.reset()
        assertEquals(3, p.slice(3))
    }
}
