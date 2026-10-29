package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RifeAiEngineTest {

    @Test
    fun modelSizeReturnsValidMultipleOf32Dimensions() {
        val (mw, mh) = RifePacking.modelSize(1920, 1080, 640)
        assertEquals(0, mw % 32)
        assertEquals(0, mh % 32)
        assertEquals(640, mw)
        assertEquals(352, mh)
    }

    @Test
    fun isModelInstalledReturnsFalseWhenContextIsNull() {
        assertFalse(RifeAiEngine.isModelInstalled(null))
    }
}
