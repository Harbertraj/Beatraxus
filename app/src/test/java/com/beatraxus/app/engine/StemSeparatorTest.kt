package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StemSeparatorTest {

    @Test
    fun overlapAddOutputLengthEqualsInputLength() {
        val numFrames = 1_000_000
        val inputPcm = FloatArray(numFrames * 2) { 0.5f }
        val chunkSize = 441000
        val hopSize = 220500

        val result = StemSeparator.processOverlapAdd(
            inputPcm = inputPcm,
            chunkSizeFrames = chunkSize,
            hopSizeFrames = hopSize
        ) { inChunk, _ ->
            // Identity chunk processor: returns inChunk repeated for 4 stems
            Array(4) { Array(2) { ch -> inChunk[ch].copyOf() } }
        }

        assertNotNull(result)
        assertEquals(4, result!!.size)

        for (s in 0 until 4) {
            assertEquals(inputPcm.size, result[s].size)
        }
    }

    @Test
    fun overlapAddConstantSignalStaysConstant() {
        val numFrames = 882000 // Exactly 20 seconds @ 44.1 kHz
        val constantValue = 0.75f
        val inputPcm = FloatArray(numFrames * 2) { constantValue }
        val chunkSize = 441000
        val hopSize = 220500

        val result = StemSeparator.processOverlapAdd(
            inputPcm = inputPcm,
            chunkSizeFrames = chunkSize,
            hopSizeFrames = hopSize
        ) { inChunk, _ ->
            // Identity chunk processor: output stem = input chunk
            Array(4) { Array(2) { ch -> inChunk[ch].copyOf() } }
        }

        assertNotNull(result)

        // Check first stem
        val stem0 = result!![0]
        assertEquals(inputPcm.size, stem0.size)

        // Verify constant signal is preserved across all samples
        for (i in 0 until stem0.size) {
            assertEquals(constantValue, stem0[i], 1e-3f)
        }
    }
}
