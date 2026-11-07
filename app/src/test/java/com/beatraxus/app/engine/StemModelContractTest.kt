package com.beatraxus.app.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemModelContractTest {
    private val goodIn = longArrayOf(1, 2, 343_980)
    private val goodOut = longArrayOf(1, 6, 2, 343_980)

    @Test
    fun acceptsTheHtdemucs6sShapes() {
        assertNull(StemModelContract.verifyHtDemucs6s(goodIn, goodOut))
    }

    @Test
    fun acceptsDynamicDimensions() {
        assertNull(StemModelContract.verifyHtDemucs6s(longArrayOf(-1, 2, -1), longArrayOf(-1, 6, 2, -1)))
    }

    @Test
    fun rejectsTheFourSourceModelWithAClearMessage() {
        val msg = StemModelContract.verifyHtDemucs6s(goodIn, longArrayOf(1, 4, 2, 343_980))
        assertNotNull(msg)
        assertTrue(msg!!.contains("6-source"))
    }

    @Test
    fun rejectsWrongSegmentLengthMonoAndNonTensors() {
        assertNotNull(StemModelContract.verifyHtDemucs6s(longArrayOf(1, 2, 441_000), longArrayOf(1, 6, 2, 441_000)))
        assertNotNull(StemModelContract.verifyHtDemucs6s(longArrayOf(1, 1, 343_980), goodOut))
        assertNotNull(StemModelContract.verifyHtDemucs6s(longArrayOf(2, 343_980), goodOut))
        assertNotNull(StemModelContract.verifyHtDemucs6s(null, goodOut))
        assertNotNull(StemModelContract.verifyHtDemucs6s(goodIn, null))
    }
}
