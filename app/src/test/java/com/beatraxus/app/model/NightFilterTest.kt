package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightFilterTest {

    @Test
    fun offNeverAndOnAlwaysApply() {
        for (h in 0..23) {
            assertFalse(NightFilterSettings(NightFilterMode.OFF).isActive(h))
            assertTrue(NightFilterSettings(NightFilterMode.ON).isActive(h))
        }
    }

    @Test
    fun autoWindowPassesMidnight() {
        val s = NightFilterSettings(NightFilterMode.AUTO) // 20 -> 7
        assertTrue(s.isActive(20))
        assertTrue(s.isActive(23))
        assertTrue(s.isActive(0))
        assertTrue(s.isActive(6))
        assertFalse(s.isActive(7))
        assertFalse(s.isActive(12))
        assertFalse(s.isActive(19))
    }

    @Test
    fun windowWithinOneDayAndEmptyWindow() {
        assertTrue(NightFilterMath.inWindow(13, 12, 14))
        assertFalse(NightFilterMath.inWindow(14, 12, 14))
        assertFalse(NightFilterMath.inWindow(5, 5, 5))
        assertTrue(NightFilterMath.inWindow(25, 0, 3)) // 25 wraps to 1
    }

    @Test
    fun strengthIsClampedAndAlphaStaysInRange() {
        assertEquals(0, NightFilterMath.clampStrength(-10))
        assertEquals(100, NightFilterMath.clampStrength(500))
        assertEquals(NightFilterMath.MIN_ALPHA, NightFilterMath.alpha(0), 0.0001f)
        assertEquals(NightFilterMath.MAX_ALPHA, NightFilterMath.alpha(100), 0.0001f)
        assertTrue(NightFilterMath.alpha(50) > NightFilterMath.alpha(10))
    }

    @Test
    fun overlayColourIsWarmAndGrowsStronger() {
        val weak = NightFilterMath.overlayArgb(0)
        val strong = NightFilterMath.overlayArgb(100)
        fun a(c: Int) = (c ushr 24) and 0xFF
        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun b(c: Int) = c and 0xFF
        assertEquals(255, r(weak)); assertEquals(255, r(strong))
        assertEquals(0, b(weak)); assertEquals(0, b(strong))
        assertTrue(g(strong) < g(weak))
        assertTrue(a(strong) > a(weak))
        assertEquals(Math.round(0.55f * 255f), a(strong))
    }

    @Test
    fun multiplyColourCutsBlueMostAndKeepsRed() {
        fun a(c: Int) = (c ushr 24) and 0xFF
        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun b(c: Int) = c and 0xFF
        val none = NightFilterMath.multiplyArgb(0)
        val mid = NightFilterMath.multiplyArgb(50)
        val full = NightFilterMath.multiplyArgb(100)
        // Strength 0 is white: multiplying by it changes nothing.
        assertEquals(0xFFFFFFFF.toInt(), none)
        for (c in listOf(none, mid, full)) {
            assertEquals(255, a(c))
            assertEquals(255, r(c))
        }
        // Blue falls faster than green, and both fall as the strength grows.
        assertTrue(b(mid) < g(mid))
        assertTrue(b(full) < b(mid) && b(mid) < b(none))
        assertTrue(g(full) < g(mid) && g(mid) < g(none))
        assertEquals(64.0, b(full).toDouble(), 1.0) // 25 % of 255
        assertEquals(178.5, g(full).toDouble(), 1.0) // 70 % of 255
        // Out of range strengths are clamped.
        assertEquals(full, NightFilterMath.multiplyArgb(500))
        assertEquals(none, NightFilterMath.multiplyArgb(-5))
    }

    @Test
    fun modeFromNameFallsBackToOff() {
        assertEquals(NightFilterMode.OFF, NightFilterMode.fromName(null))
        assertEquals(NightFilterMode.OFF, NightFilterMode.fromName("x"))
        assertEquals(NightFilterMode.AUTO, NightFilterMode.fromName("AUTO"))
    }
}
