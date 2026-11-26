package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MultipleModeTest {
    private val hz120 = listOf(60f, 120f)
    private val hz90 = listOf(60f, 90f, 120f)
    private val hz60 = listOf(60f)
    private fun caps(rates: List<Float>) = DeviceCaps(rates, glEs3 = true, floatRenderTargets = true)
    private fun src(fps: Float) = SourceInfo(fps, 1920, 1080, false)
    private val multiple = FpsBoosterSettings(mode = BoosterMode.ON, style = BoosterStyle.MULTIPLE, engine = BoosterEngine.OPTICAL_FLOW)

    private fun assertMultiplier(source: Float, target: Int, expected: Double) =
        assertEquals("$source -> $target", expected, MultipleMode.multiplier(target, source), 1e-9)

    // ---- required multipliers (target / source, computed, never looked up) ----

    @Test fun m25to60() = assertMultiplier(25f, 60, 2.4)
    @Test fun m25to90() = assertMultiplier(25f, 90, 3.6)
    @Test fun m25to120() = assertMultiplier(25f, 120, 4.8)
    @Test fun m24to60() = assertMultiplier(24f, 60, 2.5)
    @Test fun m24to90() = assertMultiplier(24f, 90, 3.75)
    @Test fun m24to120() = assertMultiplier(24f, 120, 5.0)
    @Test fun m30to60() = assertMultiplier(30f, 60, 2.0)
    @Test fun m30to90() = assertMultiplier(30f, 90, 3.0)
    @Test fun m30to120() = assertMultiplier(30f, 120, 4.0)
    @Test fun m50to120() = assertMultiplier(50f, 120, 2.4)
    @Test fun otherTableRows() {
        assertMultiplier(40f, 60, 1.5); assertMultiplier(40f, 90, 2.25); assertMultiplier(40f, 120, 3.0)
        assertMultiplier(50f, 60, 1.2); assertMultiplier(50f, 90, 1.8)
    }

    @Test fun nominalRatesAreAcceptedWithoutHardcoding() {
        assertEquals(2.5025, MultipleMode.multiplier(60, 23.976f), 1e-3)
        assertEquals(2.002, MultipleMode.multiplier(60, 29.97f), 1e-3)
        assertEquals(2.002, MultipleMode.multiplier(120, 59.94f), 1e-3)
    }

    @Test fun unknownSourceHasNoMultiplier() {
        assertEquals(0.0, MultipleMode.multiplier(60, 0f), 0.0)
        assertEquals(0.0, MultipleMode.multiplier(0, 24f), 0.0)
        assertNull(MultipleMode.ratio(60, 0f))
    }

    @Test fun multiplierText() {
        assertEquals("2.4x", MultipleMode.formatMultiplier(2.4))
        assertEquals("5x", MultipleMode.formatMultiplier(5.0))
        assertEquals("3.75x", MultipleMode.formatMultiplier(3.75))
        assertEquals("2.5x", MultipleMode.formatMultiplier(60 / 23.976 ))
        assertEquals("60 FPS · 2.4x", MultipleOption(60, 2.4).label)
    }

    // ---- ratios (the repeating output : source pattern) ----

    @Test fun ratiosAreInLowestTerms() {
        assertEquals(FrameRatio(12, 5), MultipleMode.ratio(60, 25f))
        assertEquals(FrameRatio(18, 5), MultipleMode.ratio(90, 25f))
        assertEquals(FrameRatio(24, 5), MultipleMode.ratio(120, 25f))
        assertEquals(FrameRatio(5, 2), MultipleMode.ratio(60, 24f))
        assertEquals(FrameRatio(15, 4), MultipleMode.ratio(90, 24f))
        assertEquals(FrameRatio(5, 1), MultipleMode.ratio(120, 24f))
        assertEquals(FrameRatio(2, 1), MultipleMode.ratio(60, 30f))
        assertEquals(FrameRatio(3, 2), MultipleMode.ratio(60, 40f))
        assertEquals(FrameRatio(6, 5), MultipleMode.ratio(60, 50f))
    }

    @Test fun ntscRatesResolveToTheirNominalPattern() {
        assertEquals(FrameRatio(5, 2), MultipleMode.ratio(60, 23.976f))
        assertEquals(FrameRatio(2, 1), MultipleMode.ratio(60, 29.97f))
        assertEquals(FrameRatio(2, 1), MultipleMode.ratio(120, 59.94f))
        assertTrue(MultipleMode.ratio(120, 24f)!!.isInteger)
        assertFalse(MultipleMode.ratio(60, 25f)!!.isInteger)
    }

    @Test fun noRatioWhenTheSourceIsAtOrAboveTheTarget() {
        assertNull(MultipleMode.ratio(60, 60f)); assertNull(MultipleMode.ratio(60, 75f))
    }

    @Test fun anOddRateWithoutASmallPatternHasNoRatioButIsNotAnError() {
        assertNull(MultipleMode.ratio(60, 37.31f))      // variable-rate style content: placed by timestamps, nothing to lock to
    }

    // ---- options ----

    @Test fun twentyFiveFpsOffersEveryTargetWithItsOwnMultiplier() {
        val o = MultipleMode.options(25f, hz90, 120)
        assertEquals(listOf(30, 45, 60, 90, 120), o.map { it.targetFps })
        assertEquals(listOf(1.2, 1.8, 2.4, 3.6, 4.8), o.map { it.multiplier })
        assertEquals("60 FPS · 2.4x", o.first { it.targetFps == 60 }.label)
        assertEquals("120 FPS · 4.8x", o.first { it.targetFps == 120 }.label)
    }

    @Test fun onlyTargetsTheDisplayShowsEvenlyAreEnabled() {
        assertEquals(listOf(30, 60, 120), MultipleMode.options(25f, hz120, 120).map { it.targetFps })   // 45 / 90 would judder on 60 / 120
        assertEquals(listOf(30, 60), MultipleMode.options(25f, hz60, 120).map { it.targetFps })
    }

    @Test fun optionsFollowTheSourceRateSoNoMultiplierIsStale() {
        assertEquals(2.5, MultipleMode.options(24f, hz120, 120).first { it.targetFps == 60 }.multiplier, 1e-9)
        assertEquals(2.0, MultipleMode.options(30f, hz120, 120).first { it.targetFps == 60 }.multiplier, 1e-9)
        assertEquals(1.5, MultipleMode.options(40f, hz120, 120).first { it.targetFps == 60 }.multiplier, 1e-9)
    }

    @Test fun targetsTooCloseToTheSourceAreNotOffered() {
        assertEquals(listOf(60, 120), MultipleMode.options(50f, hz120, 120).map { it.targetFps })
        assertTrue(MultipleMode.options(60f, hz120, 120).map { it.targetFps }.none { it == 60 })
        assertEquals(listOf(120), MultipleMode.options(60f, hz120, 120).map { it.targetFps })
    }

    @Test fun ceilingAndUnknownSourceLimitTheOptions() {
        assertEquals(listOf(30, 60), MultipleMode.options(24f, hz120, 60).map { it.targetFps })
        assertTrue(MultipleMode.options(0f, hz120, 120).isEmpty())
    }

    // ---- source rate snapping (unchanged) ----

    @Test fun measuredRatesSnapToTheNominalOnes() {
        assertEquals(25f, SourceRate.snap(24.8f), 0f)
        assertEquals(25f, SourceRate.snap(25.1f), 0f)
        assertEquals(23.976f, SourceRate.snap(23.9f), 0f)
        assertEquals(24f, SourceRate.snap(24.05f), 0f)
        assertEquals(23.976f, SourceRate.snap(23.98f), 0f)
        assertEquals(29.97f, SourceRate.snap(29.8f), 0f)
        assertEquals(37.3f, SourceRate.snap(37.3f), 0f)
        assertEquals(0f, SourceRate.snap(0f), 0f)
    }

    // ---- planner ----

    @Test fun fractionalTargetsAreNoLongerSentToGenerate() {
        // The reported bug: 25 FPS on a 60 / 90 / 120 Hz panel used to fall back to Generate.
        val p = FpsBoosterPlanner.plan(multiple.copy(multipleTarget = 60), caps(hz90), src(25f))
        assertTrue(p.active); assertTrue(p.phaseLock)
        assertEquals(60, p.targetFps)
        assertTrue(p.note!!, p.note!!.contains("2.4x"))
    }

    @Test fun autoPicksTheHighestSuitableTargetAndLetsTheGovernorLowerIt() {
        val p = FpsBoosterPlanner.plan(multiple, caps(hz90), src(25f))      // multipleTarget 0 = Auto
        assertTrue(p.phaseLock); assertTrue(p.auto)
        assertEquals(120, p.targetFps)
        assertEquals(listOf(30, 45, 60, 90, 120), p.candidates)
        assertTrue(p.note!!.contains("4.8x"))
    }

    @Test fun autoModeUsesTheSamePlan() {
        val p = FpsBoosterPlanner.plan(multiple.copy(mode = BoosterMode.AUTO), caps(hz120), src(24f))
        assertEquals(120, p.targetFps); assertTrue(p.auto); assertEquals(listOf(30, 60, 120), p.candidates)
    }

    @Test fun plannerHonoursAManualTargetAndCanStepDownToSmallerOnes() {
        val p = FpsBoosterPlanner.plan(multiple.copy(multipleTarget = 90), caps(hz90), src(30f))
        assertEquals(90, p.targetFps)
        assertEquals(listOf(45, 60, 90), p.candidates)
        assertFalse(p.auto); assertTrue(p.phaseLock)
        assertTrue(p.note!!.contains("3x"))
    }

    @Test fun plannerSaysSoAndPicksASafeTargetWhenTheChosenOneDoesNotFit() {
        val p = FpsBoosterPlanner.plan(multiple.copy(multipleTarget = 90), caps(hz120), src(24f))   // 90 needs a 90 Hz mode
        assertEquals(60, p.targetFps)
        assertTrue(p.note!!.contains("90 FPS")); assertTrue(p.note!!.contains("60 FPS"))
    }

    @Test fun anAlreadySmoothSourceFallsBackToGenerateWithAnExplanation() {
        val p = FpsBoosterPlanner.plan(multiple.copy(multipleTarget = 60), caps(hz60), src(60f))
        assertFalse(p.phaseLock)
        assertFalse(p.active && p.targetFps == 60)
    }

    @Test fun unknownSourceRateUsesGenerateUntilItIsLearned() {
        val p = FpsBoosterPlanner.plan(multiple, caps(hz120), src(0f))
        assertTrue(p.active); assertFalse(p.phaseLock)
        assertTrue(p.note!!.contains("not known"))
    }

    @Test fun aMeasured24Point8IsA25FpsFile() {
        val p = FpsBoosterPlanner.plan(multiple.copy(multipleTarget = 60), caps(hz90), src(24.8f))
        assertTrue(p.phaseLock); assertTrue(p.note!!.contains("25.0 FPS x 2.4x"))
    }

    @Test fun generateStyleIsUnchanged() {
        val p = FpsBoosterPlanner.plan(multiple.copy(style = BoosterStyle.GENERATE, targetFps = 60), caps(hz120), src(24f))
        assertFalse(p.phaseLock); assertEquals(60, p.targetFps)
        val q = FpsBoosterPlanner.plan(multiple.copy(style = BoosterStyle.GENERATE, targetFps = 60), caps(hz120), src(25f))
        assertFalse(q.phaseLock); assertEquals(60, q.targetFps)
    }

    @Test fun batterySaverLimitsTheCeilingToSixty() {
        val p = FpsBoosterPlanner.plan(multiple.copy(batterySaver = true), caps(hz120), src(24f))
        assertTrue(p.phaseLock); assertEquals(60, p.targetFps)
        assertTrue(p.candidates.all { it <= 60 })
    }

    // ---- settings / legacy migration ----

    @Test fun storedStyleAndTargetAreSanitised() {
        val s = FpsBoosterSettings.fromStored("ON", 60, null, null, null, null, null, style = "MULTIPLE", multipleTarget = 9)
        assertEquals(BoosterStyle.MULTIPLE, s.style); assertEquals(0, s.multipleTarget)
        assertEquals(BoosterStyle.GENERATE, FpsBoosterSettings.fromStored("ON", 60, null, null, null, null, null).style)
        assertEquals(90, FpsBoosterSettings.fromStored("ON", 60, null, null, null, null, null, style = "MULTIPLE", multipleTarget = 90).multipleTarget)
    }

    @Test fun anOldWholeMultiplierIsNeverReadAsATargetFps() {
        // fpsb_multiple used to hold 2..5. None of these is a target, so even if one reached the new field it means Auto, not "2 FPS".
        for (legacy in 2..5) assertEquals(0, BoosterTargets.sanitizeMultiple(legacy))
        assertEquals(0, BoosterTargets.sanitizeMultiple(null)); assertEquals(0, BoosterTargets.sanitizeMultiple(0))
        assertEquals(0, BoosterTargets.sanitizeMultiple(-1))
        assertTrue(FpsBoosterPrefs.MULTIPLE != FpsBoosterPrefs.MULTIPLE_TARGET)
    }

    @Test fun migrationKeepsEveryOtherSetting() {
        val s = FpsBoosterSettings.fromStored(
            "AUTO", 90, "HYBRID", "BALANCED", false, true, false, aiFusion = true, avSync = false, style = "MULTIPLE", multipleTarget = null
        )
        assertEquals(BoosterMode.AUTO, s.mode); assertEquals(90, s.targetFps); assertEquals(BoosterEngine.HYBRID, s.engine)
        assertEquals(BoosterQuality.BALANCED, s.quality); assertFalse(s.adaptiveResolution); assertTrue(s.batterySaver)
        assertFalse(s.showStats); assertTrue(s.aiFusion); assertFalse(s.avSync)
        assertEquals(BoosterStyle.MULTIPLE, s.style); assertEquals(0, s.multipleTarget)
    }

    // ---- governor: overload and fallback keep working on the fractional plan ----

    private fun win(draw: Float = 2f) = WindowStats(
        durationNs = 1_000_000_000L, sourceFrames = 25, presented = 120, generated = 95, dropped = 0,
        avgEstimateMs = 3f, maxEstimateMs = 3f, avgDrawMs = draw, displayHz = 120f
    )

    @Test fun anOverloadedDeviceStepsTheFractionalTargetDownAndRecoversGradually() {
        val plan = FpsBoosterPlanner.plan(multiple, caps(hz90), src(25f))
        val g = TargetGovernor.forPlan(plan, learned = null)
        assertEquals(120, g.target)
        // Severe load at the floor of the quality ladder: auto steps down at once, to the next candidate.
        val d = g.onWindow(LoadVerdict.SEVERE, 3, true, ThermalLevel.NONE, win())
        assertTrue(d.changed); assertEquals(90, d.target); assertEquals(TargetChangeReason.OVERLOAD, d.reason)
        // Recovery needs a long calm run: nothing moves up in the first windows.
        repeat(TargetGovernor.UP_AFTER - 1) {
            assertFalse(g.onWindow(LoadVerdict.CALM, 0, true, ThermalLevel.NONE, win()).changed)
        }
        // ... and the target is never above what the plan allows.
        assertTrue(g.target <= plan.ceilingFps)
    }

    @Test fun theGovernorNeverLeavesThePlansCandidates() {
        val plan = FpsBoosterPlanner.plan(multiple.copy(multipleTarget = 60), caps(hz90), src(25f))
        assertEquals(listOf(30, 45, 60), plan.candidates)
        val g = TargetGovernor.forPlan(plan, learned = null)
        repeat(200) { g.onWindow(LoadVerdict.CALM, 0, true, ThermalLevel.NONE, win()) }
        assertEquals(60, g.target)   // manual: the user's choice is the ceiling
    }

    // ---- phase servo ----

    /**
     * Content positions of a source -> target stream: output k at [offset] (share of a source interval) + k * source / target,
     * with the servo's shift applied and a little jitter. Returns the phases (0..1) seen, the way the renderer sees them.
     */
    private fun run(
        sourceFps: Int, targetFps: Int, offset: Double, frames: Int = 1500, jitterMs: Double = 0.1, lock: PhaseLock = PhaseLock()
    ): List<Double> {
        val ratio = MultipleMode.ratio(targetFps, sourceFps.toFloat())!!
        val interval = 1_000_000_000L / sourceFps
        val step = 1e9 / targetFps
        val rnd = java.util.Random(7)
        val out = ArrayList<Double>()
        for (k in 0 until frames) {
            val t = k * step + offset * interval + lock.offsetNs + rnd.nextGaussian() * jitterMs * 1e6
            val alpha = (t / interval) - Math.floor(t / interval)
            lock.observe(alpha.toFloat(), ratio.outputs, interval)
            out += alpha
        }
        return out
    }

    private fun gridError(alpha: Double, grid: Int): Double = abs(alpha * grid - Math.round(alpha * grid))

    @Test fun anArbitraryPhaseConvergesToTheGridForEveryFractionalRatio() {
        for ((s, t) in listOf(25 to 60, 25 to 90, 25 to 120, 24 to 60, 24 to 90, 40 to 90, 50 to 120)) {
            val grid = MultipleMode.ratio(t, s.toFloat())!!.outputs
            val ph = run(s, t, 0.5 / grid + 0.013, frames = 4000)
            val mean = ph.takeLast(300).map { gridError(it, grid) }.average()   // mean: the test jitter has outliers by design
            assertTrue("$s -> $t: remaining error $mean grid steps", mean < 0.1)
        }
    }

    @Test fun wholeRatiosStillConverge() {
        val ph = run(24, 120, 0.37, frames = 3000)
        assertTrue(ph.takeLast(300).map { gridError(it, 5) }.average() < 0.1)
    }

    @Test fun fractionalPositionsAreNotSnappedToAnIntegerGrid() {
        // 25 -> 60: phases are j/12 only after alignment, and place() must hand back the exact timestamp position.
        val r = FrameRatio(12, 5)
        assertEquals(0.4166f, PhaseLock.place(0.4166f, r), 0f)
        assertEquals(0.30f, PhaseLock.place(0.30f, r), 0f)          // an off-grid value stays what it is
        assertEquals(0f, PhaseLock.place(0.001f, r), 0f)            // a hair from a real frame IS the real frame
        assertEquals(1f, PhaseLock.place(0.999f, r), 0f)
        // Whole ratios keep the old behaviour.
        assertEquals(0.4f, PhaseLock.place(0.37f, FrameRatio(5, 1)), 1e-6f)
    }

    @Test fun offsetIsBoundedAndSlewLimited() {
        val interval = 1_000_000_000L / 25
        val lock = PhaseLock()
        var last = 0L
        var maxMove = 0L
        for (i in 0 until 400) {
            lock.observe(0.5f / 12 + (i % 12) / 12f, 12, interval)
            maxMove = maxOf(maxMove, abs(lock.offsetNs - last)); last = lock.offsetNs
            assertTrue(abs(lock.offsetNs) <= interval / 12)
        }
        assertTrue(maxMove <= interval / 12 * PhaseLock.SLEW + 1)
    }

    @Test fun resetClearsTheServo() {
        val lock = PhaseLock()
        repeat(300) { lock.observe(0.1f, 12, 40_000_000L) }
        assertTrue(lock.offsetNs != 0L)
        lock.reset()
        assertEquals(0L, lock.offsetNs)
    }

    @Test fun nothingIsLockedBelowTwoPerPattern() {
        val lock = PhaseLock()
        repeat(100) { lock.observe(0.37f, 1, 41_666_666L) }
        assertEquals(0L, lock.offsetNs)
        assertEquals(0.37f, PhaseLock.place(0.37f, FrameRatio(1, 1)), 0f)
    }

    @Test fun aNewSourceRateStartsFromACleanServo() {
        val lock = PhaseLock()
        run(25, 60, 0.31, frames = 600, lock = lock)
        assertTrue(lock.offsetNs != 0L)
        lock.reset()                                  // what the renderer does on a seek, a restart or a rate change
        assertEquals(0L, lock.offsetNs)
        run(24, 60, 0.31, frames = 600, lock = lock)
        assertTrue(abs(lock.offsetNs) <= 1_000_000_000L / 24 / 5)   // bounded by one grid step of the NEW pattern (5 : 2)
    }
}
