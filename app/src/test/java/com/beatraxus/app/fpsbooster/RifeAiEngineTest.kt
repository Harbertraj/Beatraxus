package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RifeAiEngineTest {

    @Test
    fun modelWidthAndHeightMatchQualityPreset() {
        val engine = RifeAiEngine(android.content.ContextWrapper(null))
        // Before initialization, defaults to PERFORMANCE dimensions (640x360)
        assertEquals(640, engine.modelWidth)
        assertEquals(360, engine.modelHeight)
    }

    @Test
    fun isModelInstalledReturnsFalseWhenAssetsNotPresent() {
        val context = android.content.ContextWrapper(null)
        assertFalse(RifeAiEngine.isModelInstalled(context))
    }
}
