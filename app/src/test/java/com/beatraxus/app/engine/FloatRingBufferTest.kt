package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatRingBufferTest {

    @Test
    fun testSkipWhenFullDoesNotBlock() {
        val buffer = FloatRingBuffer(1024)
        val data = FloatArray(1024) { 1f }
        
        // Fill buffer
        buffer.write(data, 1024)
        assertEquals(1024, buffer.availableRead())
        
        // Skip some
        val skipped = buffer.skip(512)
        assertEquals(512, skipped)
        assertEquals(512, buffer.availableRead())
    }
}
