package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinterlaceModeTest {

    @Test
    fun fromNameFallsBackToOff() {
        assertEquals(DeinterlaceMode.OFF, DeinterlaceMode.fromName(null))
        assertEquals(DeinterlaceMode.OFF, DeinterlaceMode.fromName("nonsense"))
        assertEquals(DeinterlaceMode.AUTO, DeinterlaceMode.fromName("AUTO"))
        assertEquals(DeinterlaceMode.ALWAYS, DeinterlaceMode.fromName("ALWAYS"))
    }

    @Test
    fun onlyOffIsOff() {
        assertFalse(DeinterlaceMode.OFF.isOn)
        assertTrue(DeinterlaceMode.AUTO.isOn)
        assertTrue(DeinterlaceMode.ALWAYS.isOn)
    }

    @Test
    fun shaderValuesMatchTheGlslSwitch() {
        assertEquals(0, DeinterlaceMode.OFF.shaderValue)
        assertEquals(1, DeinterlaceMode.AUTO.shaderValue)
        assertEquals(2, DeinterlaceMode.ALWAYS.shaderValue)
    }

    @Test
    fun offLeavesThePixelAlone() {
        assertEquals(0.9f, DeinterlaceMath.apply(DeinterlaceMode.OFF, 0.1f, 0.9f, 0.1f), 0.0001f)
    }

    @Test
    fun combedLineIsRebuiltInAuto() {
        // bright line between two dark lines = classic combing
        val out = DeinterlaceMath.apply(DeinterlaceMode.AUTO, 0.1f, 0.9f, 0.1f)
        assertEquals(0.1f, out, 0.0001f)
    }

    @Test
    fun smoothGradientIsUntouchedInAuto() {
        // neighbours on opposite sides of the current value: no combing, picture detail stays
        val out = DeinterlaceMath.apply(DeinterlaceMode.AUTO, 0.2f, 0.5f, 0.8f)
        assertEquals(0.5f, out, 0.0001f)
    }

    @Test
    fun flatAreaIsUntouchedInAuto() {
        assertEquals(0.4f, DeinterlaceMath.apply(DeinterlaceMode.AUTO, 0.4f, 0.4f, 0.4f), 0.0001f)
    }

    @Test
    fun tinyNoiseBelowThresholdIsIgnored() {
        // +-0.02 luma noise gives a comb metric of 0.0004, under COMB_LOW
        assertEquals(0f, DeinterlaceMath.autoWeight(DeinterlaceMath.combMetric(0.48f, 0.5f, 0.48f)), 0f)
    }

    @Test
    fun autoWeightIsMonotonicAndClamped() {
        assertEquals(0f, DeinterlaceMath.autoWeight(0f), 0f)
        assertEquals(1f, DeinterlaceMath.autoWeight(10f), 0f)
        var last = -1f
        for (i in 0..40) {
            val w = DeinterlaceMath.autoWeight(i * 0.001f)
            assertTrue(w >= last)
            last = w
        }
    }

    @Test
    fun alwaysIsAOneTwoOneBlend() {
        assertEquals(0.5f, DeinterlaceMath.apply(DeinterlaceMode.ALWAYS, 0.1f, 0.9f, 0.1f), 0.0001f)
    }
}
