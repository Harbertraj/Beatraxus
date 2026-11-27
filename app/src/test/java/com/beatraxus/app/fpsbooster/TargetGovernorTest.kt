package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetGovernorTest {

    private val all = listOf(30, 45, 60, 90, 120)
    private val panel120 = listOf(60f, 90f, 120f)

    private fun win(draw: Float = 2f, displayHz: Float = 120f) = WindowStats(
        durationNs = 1_000_000_000L, sourceFrames = 24, presented = 60, generated = 40, dropped = 0,
        avgEstimateMs = 3f, maxEstimateMs = 3f, avgDrawMs = draw, displayHz = displayHz
    )

    private fun TargetGovernor.step(
        v: LoadVerdict, rung: Int = 0, adaptive: Boolean = true, thermal: ThermalLevel = ThermalLevel.NONE, w: WindowStats = win()
    ) = onWindow(v, rung, adaptive, thermal, w)

    private fun TargetGovernor.run(n: Int, v: LoadVerdict, rung: Int = 0, thermal: ThermalLevel = ThermalLevel.NONE, w: WindowStats = win()): List<TargetDecision> =
        List(n) { step(v, rung, true, thermal, w) }

    // ---- candidates ----

    @Test fun candidatesAreTheCleanTargetsAboveTheSource() {
        assertEquals(listOf(30, 45, 60, 90, 120), TargetGovernor.candidates(120, panel120, 24f, auto = true))
        assertEquals(listOf(30, 60, 120), TargetGovernor.candidates(120, listOf(120f), 24f, auto = true))   // 45 / 90 would judder
        assertEquals(listOf(30, 60), TargetGovernor.candidates(120, listOf(60f), 24f, auto = true))
        assertEquals(listOf(45, 60, 90, 120), TargetGovernor.candidates(120, panel120, 30f, auto = true))   // auto needs > 1.2 x source
        assertEquals(listOf(30, 45, 60), TargetGovernor.candidates(60, panel120, 24f, auto = true))         // battery-saver ceiling
    }

    @Test fun manualCandidatesStopAtTheUsersChoiceAndSkipSourcesAlreadyThere() {
        assertEquals(listOf(30, 45, 60, 90), TargetGovernor.candidates(90, panel120, 24f, auto = false))
        assertEquals(listOf(45, 60), TargetGovernor.candidates(60, panel120, 30f, auto = false))   // 30 >= 95 % of 30: bypass
    }

    @Test fun startTargetIsTheHighestCandidateAtOrBelowIt() {
        assertEquals(90, TargetGovernor(all, 100, true).target)
        assertEquals(30, TargetGovernor(all, 10, true).target)
        assertEquals(120, TargetGovernor(all, 120, true).target)
        assertEquals(60, TargetGovernor(listOf(60), 60, false).target)
        assertEquals(120, TargetGovernor(all, 60, true).also { }.ceiling)
    }

    // ---- stepping down ----

    @Test fun targetDoesNotDropWhileTheLadderCanStillPayForIt() {
        val g = TargetGovernor(all, 120, auto = true)
        repeat(30) { assertFalse(g.step(LoadVerdict.OVERLOADED, rung = 0).changed) }
        repeat(30) { assertFalse(g.step(LoadVerdict.OVERLOADED, rung = 2).changed) }
        assertEquals(120, g.target)
    }

    @Test fun autoStepsDownAfterTwoStrainedWindowsAtTheFloor() {
        val g = TargetGovernor(all, 120, auto = true)
        assertFalse(g.step(LoadVerdict.OVERLOADED, rung = 3).changed)
        val d = g.step(LoadVerdict.OVERLOADED, rung = 3)
        assertTrue(d.changed); assertEquals(90, d.target); assertEquals(TargetChangeReason.OVERLOAD, d.reason)
    }

    @Test fun aSingleGoodWindowResetsTheStrain() {
        val g = TargetGovernor(all, 120, auto = true)
        g.step(LoadVerdict.OVERLOADED, rung = 4)
        g.step(LoadVerdict.NORMAL, rung = 4)
        assertFalse(g.step(LoadVerdict.OVERLOADED, rung = 4).changed)
    }

    @Test fun severeLoadStepsDownAtOnceInAutoButNotInManual() {
        assertEquals(90, TargetGovernor(all, 120, true).step(LoadVerdict.SEVERE, rung = 3).target)
        val m = TargetGovernor(listOf(30, 45, 60, 90, 120), 120, auto = false)
        assertFalse(m.step(LoadVerdict.SEVERE, rung = 4).changed)
        assertTrue(m.step(LoadVerdict.SEVERE, rung = 4).changed)    // SEVERE_MANUAL_DOWN_AFTER = 2
    }

    @Test fun manualWaitsLongerThanAuto() {
        val g = TargetGovernor(all, 90, auto = false)
        repeat(TargetGovernor.MANUAL_DOWN_AFTER - 1) { assertFalse(g.step(LoadVerdict.OVERLOADED, rung = 4).changed) }
        assertEquals(60, g.step(LoadVerdict.OVERLOADED, rung = 4).target)
    }

    @Test fun withoutAdaptiveResolutionTheTargetIsTheOnlyLever() {
        val g = TargetGovernor(all, 120, auto = true)
        g.step(LoadVerdict.OVERLOADED, rung = 0, adaptive = false)
        assertEquals(90, g.step(LoadVerdict.OVERLOADED, rung = 0, adaptive = false).target)
    }

    @Test fun neverGoesBelowTheLowestCandidate() {
        val g = TargetGovernor(listOf(30, 60), 60, auto = true)
        val ds = g.run(40, LoadVerdict.SEVERE, rung = 4)
        assertEquals(30, g.target)
        assertEquals(1, ds.count { it.changed })
    }

    @Test fun sustainedHeatLowersAHighRateTargetButNotA60() {
        val g = TargetGovernor(all, 120, auto = true)
        val ds = g.run(TargetGovernor.THERMAL_DOWN_AFTER, LoadVerdict.NORMAL, thermal = ThermalLevel.MODERATE)
        assertTrue(ds.last().changed); assertEquals(TargetChangeReason.THERMAL, ds.last().reason); assertEquals(90, g.target)

        val g60 = TargetGovernor(all, 60, auto = true)
        assertTrue(g60.run(20, LoadVerdict.NORMAL, thermal = ThermalLevel.MODERATE).none { it.changed })
    }

    @Test fun targetChangesAreSpacedByTheCooldown() {
        val g = TargetGovernor(all, 120, auto = true)
        val ds = g.run(12, LoadVerdict.SEVERE, rung = 4)
        val at = ds.indices.filter { ds[it].changed }
        assertTrue(at.size >= 2)
        for (i in 1 until at.size) assertTrue(at[i] - at[i - 1] >= TargetGovernor.COOLDOWN)
    }

    // ---- stepping up ----

    private fun lowered120To90(): TargetGovernor {
        val g = TargetGovernor(all, 120, auto = true)
        g.step(LoadVerdict.OVERLOADED, rung = 3); g.step(LoadVerdict.OVERLOADED, rung = 3)
        assertEquals(90, g.target)
        return g
    }

    @Test fun climbsOnlyAfterAProvenCalmStreakAndOnlyOneStep() {
        val g = TargetGovernor(all, 60, auto = true)
        val ds = g.run(TargetGovernor.UP_AFTER - 1, LoadVerdict.CALM, rung = 1)
        assertTrue(ds.none { it.changed })
        val up = g.step(LoadVerdict.CALM, rung = 1)
        assertTrue(up.changed); assertEquals(90, up.target); assertEquals(TargetChangeReason.HEADROOM, up.reason)
    }

    @Test fun aNormalWindowBreaksTheCalmStreak() {
        val g = TargetGovernor(all, 60, auto = true)
        g.run(TargetGovernor.UP_AFTER - 1, LoadVerdict.CALM, rung = 1)
        g.step(LoadVerdict.NORMAL, rung = 1)
        assertFalse(g.step(LoadVerdict.CALM, rung = 1).changed)
    }

    @Test fun doesNotClimbWhileTheLadderIsStillLow() {
        val g = TargetGovernor(all, 60, auto = true)
        assertTrue(g.run(60, LoadVerdict.CALM, rung = TargetGovernor.UP_RUNG + 1).none { it.changed })
    }

    @Test fun doesNotClimbIfTheMeasuredDrawWouldNotFitTheNextBudget() {
        val g = TargetGovernor(all, 60, auto = true)
        // 90 FPS draw budget is 6.67 ms; a probe is allowed up to 1.5x that (10 ms), because the ladder gives part of it back
        assertTrue(g.run(60, LoadVerdict.CALM, rung = 0, w = win(draw = 12f)).none { it.changed })
        assertTrue(g.run(TargetGovernor.UP_AFTER, LoadVerdict.CALM, rung = 0, w = win(draw = 9f)).any { it.changed })
    }

    @Test fun doesNotClimbToARateThePanelIsNotShowingRightNow() {
        val g = TargetGovernor(all, 60, auto = true)
        assertTrue(g.run(60, LoadVerdict.CALM, rung = 0, w = win(displayHz = 60f)).none { it.changed })
        assertTrue(g.run(TargetGovernor.UP_AFTER, LoadVerdict.CALM, rung = 0, w = win(displayHz = 120f)).any { it.changed })
    }

    @Test fun doesNotClimbWhileWarm() {
        val g = TargetGovernor(all, 60, auto = true)
        assertTrue(g.run(60, LoadVerdict.CALM, rung = 0, thermal = ThermalLevel.LIGHT).none { it.changed })
    }

    @Test fun manualRecoversUpToTheUsersTargetAndNoFurther() {
        val g = TargetGovernor(listOf(30, 45, 60, 90), 90, auto = false)
        repeat(TargetGovernor.MANUAL_DOWN_AFTER) { g.step(LoadVerdict.OVERLOADED, rung = 4) }
        assertEquals(60, g.target)
        repeat(300) { g.step(LoadVerdict.CALM, rung = 0) }
        assertEquals(90, g.target)
        assertEquals(90, g.ceiling)
    }

    // ---- backoff ----

    @Test fun aTargetThatFailedIsBlockedAndThenRetried() {
        val g = lowered120To90()
        // calm from the first window: the climb is held back by the block (UP_BLOCK windows), not by the calm streak
        val ds = g.run(TargetGovernor.UP_BLOCK + 2, LoadVerdict.CALM, rung = 0)
        val firstUp = ds.indexOfFirst { it.changed }
        assertTrue("climbed at $firstUp", firstUp >= TargetGovernor.UP_BLOCK - 3)    // not after only UP_AFTER windows
        assertEquals(120, g.target)
    }

    @Test fun repeatedFailuresDoubleTheBlock() {
        val g = lowered120To90()
        g.run(TargetGovernor.UP_BLOCK + 2, LoadVerdict.CALM, rung = 0)            // back at 120 (trial)
        assertEquals(120, g.target)
        g.step(LoadVerdict.OVERLOADED, rung = 3); g.step(LoadVerdict.OVERLOADED, rung = 3)   // fails again inside the trial
        assertEquals(90, g.target)
        // the second block is twice as long: nothing for the whole first block length
        assertTrue(g.run(TargetGovernor.UP_BLOCK + 2, LoadVerdict.CALM, rung = 0).none { it.changed })
    }

    @Test fun aSustainedTrialForgetsTheFailures() {
        val g = lowered120To90()
        g.run(TargetGovernor.UP_BLOCK + 2, LoadVerdict.CALM, rung = 0)            // 120 again
        g.run(TargetGovernor.TRIAL_WINDOWS + 2, LoadVerdict.NORMAL, rung = 0)     // holds it: sustained
        g.step(LoadVerdict.OVERLOADED, rung = 3); g.step(LoadVerdict.OVERLOADED, rung = 3)
        assertEquals(90, g.target)
        val ds = g.run(TargetGovernor.UP_BLOCK + 2, LoadVerdict.CALM, rung = 0)
        assertTrue("block is back to the first length", ds.any { it.changed })
    }

    // ---- plan -> governor ----

    private fun plan(target: Int, candidates: List<Int>, auto: Boolean) =
        BoosterPlan(true, target, BoosterEngine.OPTICAL_FLOW, BoosterQuality.BALANCED, null, candidates, auto)

    @Test fun aPlanCopiedWithANewTargetStillRunsAtThatTarget() {
        val base = BoosterPlan(true, 30, BoosterEngine.OPTICAL_FLOW, BoosterQuality.BALANCED, null)     // default candidates [30]
        val copied = base.copy(targetFps = 60)
        assertEquals(60, TargetGovernor.forPlan(copied, null).target)
        assertEquals(60, copied.ceilingFps)
    }

    @Test fun forPlanStartsWhereThePhoneLastSettled() {
        val auto = plan(60, listOf(30, 45, 60, 90, 120), auto = true)
        assertEquals(60, TargetGovernor.forPlan(auto, null).target)
        assertEquals(90, TargetGovernor.forPlan(auto, 90).target)          // auto may start above the planner's 60
        val manual = plan(120, listOf(30, 60, 90, 120), auto = false)
        assertEquals(120, TargetGovernor.forPlan(manual, null).target)
        assertEquals(120, TargetGovernor.forPlan(manual, 90).target)       // the user's 120 is not lowered by an old "could not sustain it"
        assertEquals(90, TargetGovernor.forPlan(plan(90, listOf(30, 60, 90), false), 120).target)   // never above the user's choice
        assertEquals(60, TargetGovernor.forPlan(plan(60, listOf(30, 45, 60), false), 30).target)    // a learned 30 never replaces a picked 60
    }

    @Test fun aManualTargetIsNeverSteppedDown() {
        val g = TargetGovernor.forPlan(plan(60, listOf(30, 45, 60), auto = false), learned = 30)
        val w = WindowStats(1_000_000_000L, 25, 40, 20, 5, 9f, 9f, 20f, displayHz = 120f)
        repeat(100) { assertFalse(g.onWindow(LoadVerdict.SEVERE, 3, true, ThermalLevel.SEVERE, w).changed) }
        assertEquals(60, g.target)
    }

    // ---- controller hooks ----

    private fun window(draw: Float, est: Float = 3f, dropped: Int = 0, presented: Int = 90) = WindowStats(
        durationNs = 1_000_000_000L, sourceFrames = 24, presented = presented, generated = presented * 3 / 4, dropped = dropped,
        avgEstimateMs = est, maxEstimateMs = est, avgDrawMs = draw
    )

    @Test fun controllerReportsAVerdictForEveryWindow() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        c.onWindow(window(draw = 1f, est = 1f), 24f, 90f, ThermalLevel.NONE)
        assertEquals(LoadVerdict.CALM, c.verdict)
        c.onWindow(window(draw = 8f), 24f, 90f, ThermalLevel.NONE)          // > 60 % of 11.1 ms
        assertEquals(LoadVerdict.OVERLOADED, c.verdict)
        c.onWindow(window(draw = 14f), 24f, 90f, ThermalLevel.NONE)         // over the whole period
        assertEquals(LoadVerdict.SEVERE, c.verdict)
        val n = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        n.onWindow(window(draw = 5f, est = 5f), 24f, 90f, ThermalLevel.NONE)  // inside every budget, but not calm
        assertEquals(LoadVerdict.NORMAL, n.verdict)
    }

    @Test fun loweringTheTargetGivesBackTwoRungsRaisingKeepsTheRung() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false, startIndex = 4)
        c.onTargetChanged(lowered = false)
        assertEquals(4, c.index)
        c.onTargetChanged(lowered = true)
        assertEquals(2, c.index)
        c.onTargetChanged(lowered = true)
        c.onTargetChanged(lowered = true)
        assertEquals(0, c.index)                                              // never above the preset's own rung
        val b = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false, startIndex = 4)
        b.onTargetChanged(lowered = true); b.onTargetChanged(lowered = true); b.onTargetChanged(lowered = true)
        assertEquals(1, b.index)                                              // capped at Balanced
        val off = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = false, batterySaver = false)
        off.onTargetChanged(lowered = true)
        assertEquals(0, off.index)
    }

    // ---- planner ----

    private val caps120 = DeviceCaps(listOf(60f, 90f, 120f), glEs3 = true, floatRenderTargets = true)
    private val sdr24 = SourceInfo(24f, 1920, 1080, false)

    @Test fun autoPlanStartsAtSixtyAndCanReachThePanelBest() {
        val p = FpsBoosterPlanner.plan(FpsBoosterSettings(mode = BoosterMode.AUTO), caps120, sdr24)
        assertTrue(p.active); assertTrue(p.auto)
        assertEquals(60, p.targetFps); assertEquals(120, p.ceilingFps)
        assertEquals(listOf(30, 45, 60, 90, 120), p.candidates)
    }

    @Test fun autoPlanOnA60HzPanelNeverOffersMore() {
        val p = FpsBoosterPlanner.plan(FpsBoosterSettings(mode = BoosterMode.AUTO), DeviceCaps(listOf(60f), true, true), sdr24)
        assertEquals(60, p.ceilingFps); assertEquals(listOf(30, 60), p.candidates)
    }

    @Test fun batterySaverCapsAutoAtSixty() {
        val p = FpsBoosterPlanner.plan(FpsBoosterSettings(mode = BoosterMode.AUTO, batterySaver = true), caps120, sdr24)
        assertEquals(60, p.ceilingFps)
    }

    @Test fun manualPlanIsCappedAtTheUsersTarget() {
        val p = FpsBoosterPlanner.plan(FpsBoosterSettings(mode = BoosterMode.ON, targetFps = 90), caps120, sdr24)
        assertFalse(p.auto); assertEquals(90, p.targetFps); assertEquals(90, p.ceilingFps)
        assertTrue(p.candidates.all { it <= 90 })
        val p120 = FpsBoosterPlanner.plan(FpsBoosterSettings(mode = BoosterMode.ON, targetFps = 120), caps120, sdr24)
        assertEquals(120, p120.ceilingFps)
    }

    @Test fun manualPlanWhenTheTargetFallsBackKeepsTheFallbackAsCeiling() {
        val p = FpsBoosterPlanner.plan(FpsBoosterSettings(mode = BoosterMode.ON, targetFps = 120), DeviceCaps(listOf(60f), true, true), sdr24)
        assertEquals(60, p.targetFps); assertEquals(60, p.ceilingFps)
    }

    @Test fun inactivePlanStillHasSafeDefaults() {
        val p = BoosterPlan.inactive("x")
        assertEquals(listOf(0), p.candidates); assertFalse(p.auto)
    }

    // ---- closed loop: the real controller + governor against a simulated device ----

    /**
     * A device is described by what one output frame costs and how much the motion estimate costs per rung. Draw time is the
     * frame cost plus the contention of the estimate that shares the GPU. A window drops frames when the draw exceeds the period.
     */
    private class SimResult(val target: Int, val rung: Int, val changes: Int, val dropWindows: Int, val at: Map<Int, Int>) {
        fun share(t: Int, windows: Int) = (at[t] ?: 0).toFloat() / windows
    }

    private fun simulate(frameMs: Float, mode: BoosterMode, start: Int, windows: Int, userTarget: Int = 120): SimResult {
        val auto = mode == BoosterMode.AUTO
        val cands = TargetGovernor.candidates(userTarget, panel120, 24f, auto)
        val gov = TargetGovernor(cands, start, auto)
        val ctl = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        val estPerRung = floatArrayOf(5f, 3.5f, 2.2f, 1.4f, 0.8f)
        var changes = 0; var dropWindows = 0
        val at = HashMap<Int, Int>()
        repeat(windows) {
            val t = gov.target
            at[t] = (at[t] ?: 0) + 1
            val est = estPerRung[ctl.index] * (frameMs / 3f)
            val draw = frameMs + 0.4f * est
            val period = 1000f / t
            val dropped = if (draw > period) (t * 0.3f).toInt() else 0
            if (dropped > 0) dropWindows++
            val w = WindowStats(1_000_000_000L, 24, t, t * 3 / 4, dropped, est, est, draw, displayHz = 120f)
            ctl.onWindow(w, 24f, t.toFloat(), ThermalLevel.NONE)
            val d = gov.onWindow(ctl.verdict, ctl.index, true, ThermalLevel.NONE, w)
            if (d.changed) { changes++; ctl.onTargetChanged(d.target < t) }
        }
        return SimResult(gov.target, ctl.index, changes, dropWindows, at)
    }

    @Test fun aFastPhoneClimbsFromSixtyToOneTwenty() {
        val r = simulate(frameMs = 1.5f, mode = BoosterMode.AUTO, start = 60, windows = 400)
        assertEquals(120, r.target)
        assertTrue("changes=${r.changes}", r.changes <= 3)
        assertEquals(0, r.dropWindows)
    }

    @Test fun aMediumPhoneSpendsItsTimeAtNinety() {
        // 4.8 ms per output frame: 120 (5 ms budget) is out of reach even at Ultra Fast, 90 (6.7 ms) holds once the ladder eased
        val r = simulate(frameMs = 4.8f, mode = BoosterMode.AUTO, start = 60, windows = 1000)
        assertTrue("at=${r.at}", r.share(90, 1000) > 0.7f)
        assertTrue("dropWindows=${r.dropWindows} at=${r.at}", r.dropWindows <= 40)       // only the probes of 120 and the first climb
        assertTrue("changes=${r.changes}", r.changes <= 14)
    }

    @Test fun aWeakPhoneStaysAtSixtyAndNeverProbesWhatIsHopeless() {
        val r = simulate(frameMs = 9f, mode = BoosterMode.AUTO, start = 60, windows = 1000)
        assertEquals(60, r.target)
        assertEquals("at=${r.at}", 0, r.changes)            // 9 ms is far above even the 90 FPS probe limit: it never tries
        assertEquals(0, r.dropWindows)
    }

    @Test fun aManual120OnAMediumPhoneSpendsItsTimeAtWhatItCanSustain() {
        val r = simulate(frameMs = 4.8f, mode = BoosterMode.ON, start = 120, windows = 1000)
        assertTrue("at=${r.at}", r.share(90, 1000) > 0.7f)
        assertTrue("dropWindows=${r.dropWindows} at=${r.at}", r.dropWindows <= 60)
    }

    @Test fun aPhoneThatSustains120AtUltraFastKeepsIt() {
        // 4.4 ms: 120 holds at the lowest rung. That is the design: shrink the analysis before giving up the rate
        val r = simulate(frameMs = 4.4f, mode = BoosterMode.ON, start = 120, windows = 600)
        assertEquals("at=${r.at}", 120, r.target)
        assertEquals(0, r.changes)
        assertTrue("rung=${r.rung}", r.rung >= 3)
    }

    @Test fun aManual90OnAFastPhoneStaysAtNinetyNeverGoingUp() {
        val r = simulate(frameMs = 1.5f, mode = BoosterMode.ON, start = 90, windows = 400, userTarget = 90)
        assertEquals(90, r.target)
        assertEquals(0, r.changes)
    }

    @Test fun targetNotesAreNullByDefault() {
        assertNull(BoosterStats().targetNote)
        assertEquals(0, BoosterStats().requestedTargetFps)
    }
}
