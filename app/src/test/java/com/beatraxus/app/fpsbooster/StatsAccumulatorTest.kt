package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatsAccumulatorTest {
    private val sec = 1_000_000_000L

    @Test fun reportsOncePerWindowWithCorrectRates() {
        val a = StatsAccumulator()
        assertNull(a.poll(10 * sec))                 // starts the window
        repeat(24) { a.onSourceFrame() }
        repeat(60) { a.onPresented(generatedFrame = it % 5 != 0) }
        a.onDropped(3)
        a.onEstimateMs(10f); a.onEstimateMs(20f); a.onDrawMs(2f)
        assertNull(a.poll(10 * sec + sec / 2))
        val w = a.poll(11 * sec)
        assertNotNull(w); w!!
        assertEquals(24f, w.sourceFps, 0.01f)
        assertEquals(60f, w.renderedFps, 0.01f)
        assertEquals(48f, w.generatedFps, 0.01f)
        assertEquals(3, w.dropped)
        assertEquals(15f, w.avgEstimateMs, 1e-4f); assertEquals(20f, w.maxEstimateMs, 0f)
        assertEquals(2f, w.avgDrawMs, 0f)
    }

    @Test fun windowResetsAfterReporting() {
        val a = StatsAccumulator()
        a.poll(sec); a.onPresented(true)
        a.poll(2 * sec)
        assertNull(a.poll(2 * sec + 1))
        val w = a.poll(3 * sec)!!
        assertEquals(0, w.presented)
    }
}
