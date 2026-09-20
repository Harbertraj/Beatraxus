package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownmixerTest {

    @Test
    fun test5Point1ToStereoDownmix() {
        // 6 channels: L, R, C, LFE, SL, SR
        // Frame 0: L=1.0, R=0.0, C=0.5, LFE=0.5, SL=0.2, SR=0.0
        val input = floatArrayOf(
            1.0f, 0.0f, 0.5f, 0.5f, 0.2f, 0.0f
        )
        val output = FloatArray(2)

        val samplesWritten = AudioDownmixer.downmixFloat(
            input = input,
            inputSampleCount = 6,
            srcChannels = 6,
            targetChannels = 2,
            outputBuffer = output
        )

        assertEquals(2, samplesWritten)
        val expectedScale = 0.70710678f
        val expectedCoef = 0.70710678f
        val expectedL = (1.0f + 0.5f * expectedCoef + 0.2f * expectedCoef) * expectedScale
        val expectedR = (0.0f + 0.5f * expectedCoef + 0.0f * expectedCoef) * expectedScale

        assertEquals(expectedL, output[0], 0.001f)
        assertEquals(expectedR, output[1], 0.001f)
    }

    @Test
    fun testSameChannelCountCopiesData() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val output = FloatArray(4)

        val samplesWritten = AudioDownmixer.downmixFloat(
            input = input,
            inputSampleCount = 4,
            srcChannels = 2,
            targetChannels = 2,
            outputBuffer = output
        )

        assertEquals(4, samplesWritten)
        assertEquals(0.1f, output[0], 0.0001f)
        assertEquals(0.2f, output[1], 0.0001f)
        assertEquals(0.3f, output[2], 0.0001f)
        assertEquals(0.4f, output[3], 0.0001f)
    }

    @Test
    fun testMonoToStereo() {
        val input = floatArrayOf(0.5f, -0.5f)
        val output = FloatArray(4)

        val samplesWritten = AudioDownmixer.downmixFloat(
            input = input,
            inputSampleCount = 2,
            srcChannels = 1,
            targetChannels = 2,
            outputBuffer = output
        )

        assertEquals(4, samplesWritten)
        assertEquals(0.5f, output[0], 0.0001f)
        assertEquals(0.5f, output[1], 0.0001f)
        assertEquals(-0.5f, output[2], 0.0001f)
        assertEquals(-0.5f, output[3], 0.0001f)
    }
}
