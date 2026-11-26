package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MultipleModeTest {
    private val hz120 = listOf(60f, 120f)
    private val hz90 = listOf(60f, 90f, 120f)
    private fun caps(rates: List<Float>) = DeviceCaps(rates, glEs3 = true, floatRenderTargets = true)
    private fun src(fps: Float) = SourceInfo(fps, 1920, 1080, false)
    private val multiple = FpsBoosterSettings(mode = BoosterMode.ON, style = BoosterStyle.MULTIPLE, engine = BoosterEngine.OPTICAL_FLOW)

    // ---- options / phaseCount ----

    @Test fun film24On120HzOffersOnlyFiveTimes() {
        assertEquals(listOf(MultipleOption(5, 120)), MultipleMode.options(24f, hz120, 120))
    }

    @Test fun ntscFilmRateIsAccepted() {
        assertEquals(listOf(MultipleOption(5, 120)), MultipleMode.options(23.976f, hz120, 120))
    }

    @Test fun thirtyFpsGetsTwoThreeAndFourTimesOnA90HzPanel() {
        assertEquals(listOf(60, 90, 120), MultipleMode.options(30f, hz90, 120).map { it.targetFps })
        assertEquals(listOf(2, 3, 4), MultipleMode.options(30f, hz90, 120).map { it.n })
    }

    @Test fun twentyFiveFpsHasNoCleanMultipleOnA60And120HzPanel() {
        assertTrue(MultipleMode.options(25f, hz120, 120).isEmpty())
        assertTrue(MultipleMode.options(25f, hz90, 120).isEmpty())
    }

    @Test fun ceilingAndUnknownSourceLimitTheOptions() {
        assertTrue(MultipleMode.options(24f, hz120, 60).isEmpty())
        assertTrue(MultipleMode.options(0f, hz120, 120).isEmpty())
    }

    @Test fun phaseCountOnlyForWholeMultiples() {
        assertEquals(5, MultipleMode.phaseCount(120, 24f))
        assertEquals(5, MultipleMode.phaseCount(120, 23.976f))
        assertEquals(2, MultipleMode.phaseCount(60, 30f))
        assertEquals(0, MultipleMode.phaseCount(60, 24f))     // 2.5x: not a whole multiple
        assertEquals(0, MultipleMode.phaseCount(60, 25.1f))
        assertEquals(0, MultipleMode.phaseCount(30, 30f))     // 1x: nothing to generate
        assertEquals(0, MultipleMode.phaseCount(60, 0f))
    }

    // ---- measured rate ----

    @Test fun measuredRatesSnapToStandardRates() {
        assertEquals(25f, SourceRate.snap(24.8f), 0f)
        assertEquals(25f, SourceRate.snap(25.1f), 0f)
        assertEquals(23.976f, SourceRate.snap(23.9f), 0f)   // film rates 23.976 and 24 both give a 5x multiple
        assertEquals(24f, SourceRate.snap(24.05f), 0f)
        assertEquals(23.976f, SourceRate.snap(23.98f), 0f)
        assertEquals(29.97f, SourceRate.snap(29.8f), 0f)
        assertEquals(37.3f, SourceRate.snap(37.3f), 0f)    // not near a standard rate: unchanged
        assertEquals(0f, SourceRate.snap(0f), 0f)
    }

    @Test fun aMeasured24Point8IsA25FpsFileSoNoMultipleFits() {
        val p = FpsBoosterPlanner.plan(multiple, caps(hz90), src(24.8f))
        assertFalse(p.phaseLock)
        assertTrue(p.note!!.contains("25.0"))
    }

    @Test fun aMeasured23Point9IsAFilmSoFiveTimesFits() {
        val p = FpsBoosterPlanner.plan(multiple, caps(hz120), src(23.9f))
        assertTrue(p.phaseLock); assertEquals(120, p.targetFps)
    }

    // ---- planner ----

    @Test fun plannerPicksFiveTimesFor24FpsOn120Hz() {
        val p = FpsBoosterPlanner.plan(multiple, caps(hz120), src(24f))
        assertTrue(p.active); assertTrue(p.phaseLock)
        assertEquals(120, p.targetFps)
        assertEquals(listOf(120), p.candidates)
        assertTrue(p.note!!.contains("5x"))
    }

    @Test fun plannerHonoursAManualMultipleAndCanStepDownToSmallerOnes() {
        val p = FpsBoosterPlanner.plan(multiple.copy(multiple = 3), caps(hz90), src(30f))
        assertEquals(90, p.targetFps)
        assertEquals(listOf(60, 90), p.candidates)
        assertTrue(p.phaseLock)
    }

    @Test fun plannerSaysSoWhenTheChosenMultipleDoesNotFit() {
        val p = FpsBoosterPlanner.plan(multiple.copy(multiple = 3), caps(hz120), src(24f))
        assertEquals(120, p.targetFps)
        assertTrue(p.note!!.contains("3x")); assertTrue(p.note!!.contains("5x"))
    }

    @Test fun plannerFallsBackToGenerateWithAnExplanationFor25Fps() {
        val p = FpsBoosterPlanner.plan(multiple, caps(hz120), src(25f))
        assertTrue(p.active); assertFalse(p.phaseLock)
        assertTrue(p.note!!.contains("no whole multiple"))
    }

    @Test fun generateStyleIsUnchanged() {
        val p = FpsBoosterPlanner.plan(multiple.copy(style = BoosterStyle.GENERATE, targetFps = 60), caps(hz120), src(24f))
        assertFalse(p.phaseLock); assertEquals(60, p.targetFps)
    }

    @Test fun batterySaverLimitsMultiples() {
        val p = FpsBoosterPlanner.plan(multiple.copy(batterySaver = true), caps(hz120), src(24f))
        assertFalse(p.phaseLock)   // 120 is above the battery-saver ceiling of 60 and 24 has no other clean multiple
    }

    @Test fun autoModeStartsAtTheLowestMultipleAndMayClimb() {
        val p = FpsBoosterPlanner.plan(multiple.copy(mode = BoosterMode.AUTO), caps(hz90), src(30f))
        assertEquals(60, p.targetFps)
        assertEquals(listOf(60, 90, 120), p.candidates)
        assertTrue(p.auto)
    }

    @Test fun storedStyleAndMultipleAreSanitised() {
        val s = FpsBoosterSettings.fromStored("ON", 60, null, null, null, null, null, style = "MULTIPLE", multiple = 9)
        assertEquals(BoosterStyle.MULTIPLE, s.style); assertEquals(0, s.multiple)
        assertEquals(BoosterStyle.GENERATE, FpsBoosterSettings.fromStored("ON", 60, null, null, null, null, null).style)
    }

    // ---- phase lock ----

    /** Content positions of a 24 -> 120 stream with a phase [offset] (0..1 of a source interval) and a little jitter. */
    private fun run(offset: Double, frames: Int = 600, jitterMs: Double = 0.3): List<Double> {
        val interval = 1_000_000_000L / 24
        val n = 5
        val lock = PhaseLock()
        val out = ArrayList<Double>()
        val rnd = java.util.Random(7)
        for (i in 0 until frames) {
            val t = i * (interval.toDouble() / n) + offset * interval + lock.offsetNs + rnd.nextGaussian() * jitterMs * 1e6
            val alpha = (t / interval) - Math.floor(t / interval)
            out += lock.lock(alpha.toFloat(), n, interval).toDouble()
        }
        return out
    }

    @Test fun lockedPositionsAreAlwaysOnTheGrid() {
        run(0.37).forEach { a -> assertEquals(0.0, abs(a * 5 - Math.round(a * 5)), 1e-4) }
    }

    @Test fun anArbitraryPhaseConvergesSoTheServoIdlesAtTheGrid() {
        // After settling, the unsnapped alpha sits within a small fraction of a grid step from a grid point.
        val interval = 1_000_000_000L / 24
        val lock = PhaseLock()
        val n = 5
        var worst = 0.0
        for (i in 0 until 3000) {
            val t = i * (interval.toDouble() / n) + 0.5 * (interval.toDouble() / n) + lock.offsetNs   // starts half a step off: the worst case
            val alpha = ((t / interval) - Math.floor(t / interval)).toFloat()
            lock.lock(alpha, n, interval)
            if (i > 2500) worst = maxOf(worst, abs(alpha * n - Math.round(alpha * n)).toDouble())
        }
        assertTrue("remaining error $worst grid steps", worst < 0.05)
    }

    @Test fun offsetIsBoundedAndSlewLimited() {
        val interval = 1_000_000_000L / 24
        val lock = PhaseLock()
        var last = 0L
        var maxMove = 0L
        for (i in 0 until 400) {
            lock.lock(0.5f / 5 + (i % 5) / 5f, 5, interval)
            maxMove = maxOf(maxMove, abs(lock.offsetNs - last)); last = lock.offsetNs
            assertTrue(abs(lock.offsetNs) <= interval / 5)
        }
        assertTrue(maxMove <= interval / 5 * PhaseLock.SLEW + 1)
    }

    @Test fun resetClearsTheServo() {
        val lock = PhaseLock()
        repeat(300) { lock.lock(0.1f, 5, 41_666_666L) }
        assertTrue(lock.offsetNs != 0L)
        lock.reset()
        assertEquals(0L, lock.offsetNs)
    }

    @Test fun nothingIsLockedBelowTwoPerFrame() {
        assertEquals(0.37f, PhaseLock().lock(0.37f, 1, 41_666_666L), 0f)
    }
}
