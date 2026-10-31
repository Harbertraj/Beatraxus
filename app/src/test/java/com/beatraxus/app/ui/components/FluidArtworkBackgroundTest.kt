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

    @Test
    fun songChangeDriftIsSlowSmoothAndNeverOvershoots() {
        val damper = SmoothDamper(0f)
        val target = 3.0f // about half a turn, the worst case after shortestAngle
        var previousStep = 0f
        var maxStep = 0f
        var maxJerk = 0f
        var t = 0f
        val dt = 1f / 60f
        while (t < 120f) {
            val before = damper.value
            damper.step(target, dt, PHASE_SMOOTH_SECONDS, PHASE_MAX_SPEED)
            val step = damper.value - before
            assertTrue("never overshoots", damper.value <= target + 1e-4f)
            maxStep = maxOf(maxStep, step)
            maxJerk = maxOf(maxJerk, abs(step - previousStep))
            previousStep = step
            t += dt
        }
        // Never faster than the speed limit, and the speed changes gradually frame to frame.
        assertTrue(maxStep / dt <= PHASE_MAX_SPEED + 1e-3f)
        assertTrue(maxJerk < 0.0005f)
        assertEquals(target, damper.value, 0.02f)
    }

    @Test
    fun damperStaysPutWhenAlreadyAtTarget() {
        val damper = SmoothDamper(1.25f)
        repeat(100) { damper.step(1.25f, 1f / 60f, PHASE_SMOOTH_SECONDS, PHASE_MAX_SPEED) }
        assertEquals(1.25f, damper.value, 1e-5f)
    }

    @Test
    fun backgroundSettingsAreNeutralAtTheirResetValues() {
        assertEquals(1f, fluidBlurScale(NOW_PLAYING_DEFAULT_BLUR, NOW_PLAYING_DEFAULT_BLUR), 1e-6f)
        assertEquals(1f, fluidBlurScale(MINI_PLAYER_DEFAULT_BLUR, MINI_PLAYER_DEFAULT_BLUR), 1e-6f)
        assertEquals(1f, fluidBrightnessScale(NOW_PLAYING_DEFAULT_DARKNESS, NOW_PLAYING_DEFAULT_DARKNESS), 1e-6f)
        assertEquals(1f, fluidBrightnessScale(MINI_PLAYER_DEFAULT_DARKNESS, MINI_PLAYER_DEFAULT_DARKNESS), 1e-6f)
    }

    @Test
    fun blurSliderMakesTheMovingArtMoreOrLessBlurred() {
        val low = fluidBlurScale(10f, NOW_PLAYING_DEFAULT_BLUR)
        val high = fluidBlurScale(250f, NOW_PLAYING_DEFAULT_BLUR)
        assertTrue(low < 0.1f && low > 0f)
        assertTrue(high > 1f)
        // The bar's slider range (10..250) around its smaller reset value stays bounded.
        assertTrue(fluidBlurScale(250f, MINI_PLAYER_DEFAULT_BLUR) <= 3.5f)
    }

    @Test
    fun darknessSliderDarkensAndBrightensAroundTheResetValue() {
        assertEquals(0f, fluidBrightnessScale(1f, NOW_PLAYING_DEFAULT_DARKNESS), 1e-6f)
        assertTrue(fluidBrightnessScale(0.6f, NOW_PLAYING_DEFAULT_DARKNESS) < 1f)
        assertTrue(fluidBrightnessScale(0f, NOW_PLAYING_DEFAULT_DARKNESS) > 1f)
        assertTrue(fluidBrightnessScale(0f, MINI_PLAYER_DEFAULT_DARKNESS) <= 1.6f)
        // Out-of-range values are clamped instead of producing negative brightness.
        assertEquals(0f, fluidBrightnessScale(2f, NOW_PLAYING_DEFAULT_DARKNESS), 1e-6f)
    }
}
