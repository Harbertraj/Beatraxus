package com.beatraxus.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FluidArtworkBackgroundTest {

    @Test
    fun shortestAngleAlwaysTakesTheShorterWayRound() {
        assertEquals(0.5f, shortestAngle(0.5f), 1e-5f)
        assertEquals(-0.5f, shortestAngle(-0.5f), 1e-5f)
        // 350 degrees forward is 10 degrees backward.
        assertEquals(-0.1745f, shortestAngle(Math.toRadians(350.0).toFloat()), 1e-3f)
        assertEquals(0.1745f, shortestAngle(Math.toRadians(-350.0).toFloat()), 1e-3f)
        for (i in -40..40) assertTrue(abs(shortestAngle(i * 0.37f)) <= 3.1416f)
    }

    @Test
    fun renderScaleKeepsSmallAreasFullSizeAndLargeOnesReduced() {
        // Now Playing bar (about 1000 x 190 px) is drawn 1:1.
        assertEquals(1, fluidRenderScale(1000, 190))
        // A full phone screen is drawn at a fraction of its size.
        assertEquals(4, fluidRenderScale(1080, 2412))
        assertEquals(4, fluidRenderScale(2412, 1080))
        assertEquals(1, fluidRenderScale(0, 0))
    }

    @Test
    fun phaseProviderMatchesSeedLayoutAndMovesContinuously() {
        val seed = "track-1".hashCode()
        val bySeed = fluidCopies(1080f, 2412f, 12.5f, seed)
        val byPhase = fluidCopies(1080f, 2412f, 12.5f) { index ->
            // Same phases the seed variant derives.
            (((seed ushr (index * 5)) and 0x1F) / 32f) * 6.2831855f
        }
        assertEquals(bySeed, byPhase)

        // A tiny time step moves every copy only a tiny amount (no jumps).
        val a = fluidCopies(1080f, 2412f, 3.000f, seed)
        val b = fluidCopies(1080f, 2412f, 3.016f, seed)
        for (i in a.indices) {
            assertTrue(abs(a[i].centreX - b[i].centreX) < 3f)
            assertTrue(abs(a[i].centreY - b[i].centreY) < 3f)
            assertTrue(abs(a[i].angle - b[i].angle) < 0.01f)
        }
    }
}
