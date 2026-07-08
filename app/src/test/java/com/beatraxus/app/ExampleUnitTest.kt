package com.beatraxus.app

import com.beatraxus.app.engine.computeCrossfadeProgress
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun crossfadeProgress_returnsZeroForDisabledDuration() {
        assertEquals(0f, computeCrossfadeProgress(1000L, 0), 0f)
        assertEquals(0f, computeCrossfadeProgress(1000L, -1), 0f)
    }

    @Test
    fun crossfadeProgress_clampsToValidRange() {
        assertEquals(1f, computeCrossfadeProgress(10000L, 1), 0f)
        assertEquals(0f, computeCrossfadeProgress(0L, 1), 0f)
        assertTrue(computeCrossfadeProgress(500L, 1) > 0f)
    }
}
