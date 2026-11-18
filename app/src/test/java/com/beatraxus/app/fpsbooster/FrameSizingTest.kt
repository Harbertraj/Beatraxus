package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSizingTest {
    private val ladder = AdaptiveQualityController.LADDER

    @Test fun everyStepOfTheLadderKeepsTheStoredFrameSize() {
        for ((w, h) in listOf(1920 to 1080, 3840 to 2160, 1280 to 720, 854 to 480, 1080 to 1920, 1001 to 563)) {
            for (a in ladder) for (b in ladder) {
                assertTrue("$w x $h ${a.label} -> ${b.label}", FrameSizing.analysisOnlyChange(w, h, a, b))
            }
        }
    }

    @Test fun aLevelWithASmallerFrameCapStillReallocatesTheFrames() {
        val small = QualityLevel("Small", 480, 640, FlowPyramid.COARSEST_RANGE, false)
        assertFalse(FrameSizing.analysisOnlyChange(1920, 1080, ladder[0], small))
        assertEquals(640 to 360, FrameSizing.frame(1920, 1080, 640))
    }

    @Test fun workImageShrinksWithTheLevelAndIsEven() {
        val sizes = ladder.map { FrameSizing.work(1920, 1080, it.workEdge) }
        assertTrue(sizes.zipWithNext().all { (a, b) -> b.first <= a.first })
        assertTrue(sizes.all { it.first % 2 == 0 && it.second % 2 == 0 })
        assertEquals(960 to 540, FrameSizing.work(1920, 1080, 960))
    }

    @Test fun smallSourcesAreNeverUpscaled() {
        assertEquals(320 to 240, FrameSizing.frame(320, 240, 1280))
        assertEquals(320 to 240, FrameSizing.work(320, 240, 1280))
    }
}
