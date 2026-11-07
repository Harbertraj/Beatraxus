package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StemLoadProfileTest {
    @Test fun firstLevelIsNormal() {
        assertEquals(4, StemLoadProfile.threads(0))
        assertFalse(StemLoadProfile.noGraphOptimisation(0))
        assertFalse(StemLoadProfile.isMaxBlocked(0))
    }

    @Test fun oneKillMakesLoadLeaner() {
        val l = StemLoadProfile.next(0)
        assertEquals(2, StemLoadProfile.threads(l))
        assertTrue(StemLoadProfile.noGraphOptimisation(l))
        assertFalse(StemLoadProfile.isMaxBlocked(l))
    }

    @Test fun secondKillBlocksMaxAndStaysThere() {
        val l = StemLoadProfile.next(StemLoadProfile.next(0))
        assertTrue(StemLoadProfile.isMaxBlocked(l))
        assertEquals(l, StemLoadProfile.next(l))
    }
}
