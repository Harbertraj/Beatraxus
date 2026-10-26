package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPyramidTest {
    private fun coarsest(workEdge: Int): Int {
        val w = workEdge
        val h = workEdge * 9 / 16
        return FlowPyramid.levels(w, h).let { it.width[it.coarsest] }
    }

    @Test fun everyPresetEndsNearTheSameCoarsestEdge() {
        // The old rule (stop below 64) gave 40 / 60 / 40 px; Balanced alone stopped early.
        for (edge in listOf(480, 640, 960, 1280)) {
            val c = coarsest(edge)
            assertTrue("work $edge -> coarsest $c", c in 28..56)
        }
        assertTrue("Balanced no longer stops at 60", coarsest(960) < 56)
    }

    @Test fun depthIsBounded() {
        assertTrue(FlowPyramid.levels(3840, 2160).count <= FlowPyramid.MAX_LEVELS)
        assertEquals(1, FlowPyramid.levels(32, 32).count)
    }

    @Test fun globalScaleIsRelativeToCoarsest() {
        val l = FlowPyramid.levels(960, 540)
        assertEquals(1f, l.globalScale(l.coarsest), 1e-6f)
        assertEquals(960f / l.width[l.coarsest], l.globalScale(0), 1e-4f)
    }

    @Test fun exactCostOnlyOnFinestAndCoarsest() {
        val l = FlowPyramid.levels(960, 540)
        for (i in 0 until l.count) assertEquals(i == 0 || i == l.coarsest, l.needsExactCost(i))
    }

    @Test fun globalSamplesFitSmallGrids() {
        assertEquals(12 to 8, FlowPyramid.globalSamples(30, 20))
        assertEquals(5 to 3, FlowPyramid.globalSamples(5, 3))
    }

    @Test fun coarsestRangeRaised() {
        assertEquals(10, FlowPyramid.COARSEST_RANGE)
        AdaptiveQualityController.LADDER.forEach { assertEquals(10, it.searchRange) }
    }
}
