package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpolationBackendTest {

    @Test
    fun defaultProviderOrderNeverContainsNnapi() {
        // NNAPI cannot run the RIFE GridSample / ConvTranspose ops; it is opt-in after a device benchmark.
        assertFalse(InferenceProvider.DEFAULT_ORDER.contains(InferenceProvider.NNAPI))
    }

    @Test
    fun defaultProviderOrderPrefersOptimizedCpuThenPlainCpu() {
        assertEquals(listOf(InferenceProvider.XNNPACK, InferenceProvider.CPU), InferenceProvider.DEFAULT_ORDER)
    }

    @Test
    fun everyProviderHasALabel() {
        assertTrue(InferenceProvider.values().all { it.label.isNotBlank() })
    }
}
