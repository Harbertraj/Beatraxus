package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridSchedulingTest {

    @Test
    fun computeRifeStrideAdaptsToInferenceLatency() {
        val src41ms = 41_666_667L // 24 FPS source interval

        // Fast inference (20ms <= 41.6ms) -> stride 1 (every pair)
        assertEquals(1, HybridScheduling.computeRifeStride(20_000_000L, src41ms))

        // Medium inference (60ms > 41.6ms and <= 83.3ms) -> stride 2 (every 2nd pair)
        assertEquals(2, HybridScheduling.computeRifeStride(60_000_000L, src41ms))

        // Slow inference (100ms > 83.3ms) -> stride 3 (every 3rd pair)
        assertEquals(3, HybridScheduling.computeRifeStride(100_000_000L, src41ms))

        // Invalid / unknown values fallback to 1
        assertEquals(1, HybridScheduling.computeRifeStride(0L, src41ms))
        assertEquals(1, HybridScheduling.computeRifeStride(20_000_000L, 0L))
    }

    @Test
    fun isRifeValidValidatesTargetTimestampAndGeneration() {
        val ts100 = 100_000_000L
        val ts200 = 200_000_000L

        // Valid when timestamps match, generation matches, and no cut
        assertTrue(HybridScheduling.isRifeValid(ts100, ts100, generation = 1, activeGeneration = 1, isCut = false))

        // Invalid when target frame timestamp differs
        assertFalse(HybridScheduling.isRifeValid(ts100, ts200, generation = 1, activeGeneration = 1, isCut = false))

        // Invalid when generation mismatch (e.g. after seek or target change)
        assertFalse(HybridScheduling.isRifeValid(ts100, ts100, generation = 1, activeGeneration = 2, isCut = false))

        // Invalid during scene cut
        assertFalse(HybridScheduling.isRifeValid(ts100, ts100, generation = 1, activeGeneration = 1, isCut = true))
    }

    @Test
    fun hallucinationGuardConfidenceGivesFullWeightWhenInBounds() {
        val a = floatArrayOf(0.2f, 0.4f, 0.6f)
        val b = floatArrayOf(0.8f, 0.5f, 0.3f)
        // RIFE prediction between min(A,B) and max(A,B)
        val rifeInBounds = floatArrayOf(0.5f, 0.45f, 0.4f)

        val conf = HybridScheduling.hallucinationGuardConfidence(rifeInBounds, a, b)
        assertEquals(1.0f, conf, 1e-4f)
    }

    @Test
    fun hallucinationGuardConfidenceFadesWhenOutofBounds() {
        val a = floatArrayOf(0.2f, 0.4f, 0.6f)
        val b = floatArrayOf(0.8f, 0.5f, 0.3f)

        // RIFE prediction far outside [0.2, 0.8] in red channel (e.g., 0.95 -> error = 0.95 - 0.82 = 0.13 > 0.08)
        val rifeHallucinated = floatArrayOf(0.95f, 0.45f, 0.4f)
        val confZero = HybridScheduling.hallucinationGuardConfidence(rifeHallucinated, a, b)
        assertEquals(0.0f, confZero, 1e-4f)

        // Mildly out of bounds -> partial confidence
        val rifeMild = floatArrayOf(0.86f, 0.45f, 0.4f) // err = 0.86 - 0.82 = 0.04 -> conf = 1 - 0.04/0.08 = 0.5
        val confPartial = HybridScheduling.hallucinationGuardConfidence(rifeMild, a, b)
        assertEquals(0.5f, confPartial, 1e-2f)
    }

    @Test
    fun midFrameBudgetAndTimestamp() {
        val src = 41_666_667L
        assertTrue(HybridScheduling.canInjectMid(0L, src))              // not measured yet: try
        assertTrue(HybridScheduling.canInjectMid(30_000_000L, src))
        assertFalse(HybridScheduling.canInjectMid(45_000_000L, src))    // slower than 90 % of an interval
        assertEquals(150L, HybridScheduling.midTimestampNs(100L, 200L))
    }

    @Test
    fun meanLumaDiffDetectsCuts() {
        val w = 64
        val h = 36
        val black = java.nio.ByteBuffer.allocate(w * h * 4)
        val white = java.nio.ByteBuffer.allocate(w * h * 4)
        for (i in 0 until w * h * 4) white.put(i, 0xFF.toByte())
        assertEquals(0f, HybridScheduling.meanLumaDiff(black, black, w, h), 1e-4f)
        assertEquals(1f, HybridScheduling.meanLumaDiff(black, white, w, h), 1e-3f)
        assertTrue(HybridScheduling.meanLumaDiff(black, white, w, h) > HybridScheduling.CUT_MEAN_DIFF)
    }
}
