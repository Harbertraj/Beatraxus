package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionComplexityTest {

    private val edge = 960

    /** dx in px, support, meanCost, poorShare, cut verdict. */
    private fun MotionComplexityClassifier.pair(
        dx: Float = 0f, support: Float = 1f, cost: Float = 0.002f, poor: Float = 0f, cut: Float = 0f
    ) = onPair(dx, 0f, support, cost, poor, cut, edge)

    @Test fun nothingMovingIsStatic() {
        assertEquals(MotionComplexity.STATIC, MotionComplexityClassifier().pair())
    }

    @Test fun smoothCameraMoveIsSlowAndAFastPanIsFast() {
        assertEquals(MotionComplexity.SLOW, MotionComplexityClassifier().pair(dx = 0.008f * edge))
        assertEquals(MotionComplexity.FAST, MotionComplexityClassifier().pair(dx = 0.03f * edge))
    }

    @Test fun badMatchesOrNoDominantMotionAreComplex() {
        assertEquals(MotionComplexity.COMPLEX, MotionComplexityClassifier().pair(poor = 0.3f))
        assertEquals(MotionComplexity.COMPLEX, MotionComplexityClassifier().pair(cost = 0.03f))
        assertEquals(MotionComplexity.COMPLEX, MotionComplexityClassifier().pair(dx = 0.01f * edge, support = 0.2f))
    }

    @Test fun aMovingSubjectOnAStillBackgroundIsNotCalledStaticWhenItMatchesBadly() {
        // static camera (zero dominant vector) but a subject that breaks the match: must not drop to the static plan
        val c = MotionComplexityClassifier()
        assertEquals(MotionComplexity.SLOW, c.pair(support = 0.7f))                 // not enough agreement for STATIC
        assertEquals(MotionComplexity.COMPLEX, MotionComplexityClassifier().pair(support = 0.9f, poor = 0.2f))
    }

    @Test fun hardCutReportsCutOnceAndRestartsTheScene() {
        val c = MotionComplexityClassifier()
        c.pair(dx = 0.03f * edge)
        assertEquals(MotionComplexity.CUT, c.pair(cut = 1f))
        assertEquals(MotionComplexity.UNKNOWN, c.current)
        assertEquals(MotionComplexity.STATIC, c.pair())           // new scene is judged afresh, no stale FAST
    }

    @Test fun whipPanIsFastNotCut() {
        assertEquals(MotionComplexity.FAST, MotionComplexityClassifier().pair(cut = 2f))
    }

    @Test fun harderIsAdoptedAtOnceEasierOnlyAfterAStreak() {
        val c = MotionComplexityClassifier()
        assertEquals(MotionComplexity.FAST, c.pair(dx = 0.03f * edge))
        assertEquals(MotionComplexity.FAST, c.pair())              // 1 quiet pair
        assertEquals(MotionComplexity.FAST, c.pair())              // 2
        assertEquals(MotionComplexity.SLOW, c.pair())              // 3 -> one class down, not straight to STATIC
        repeat(2) { assertEquals(MotionComplexity.SLOW, c.pair()) }
        assertEquals(MotionComplexity.STATIC, c.pair())
    }

    @Test fun aBriefQuietPairDoesNotResetTheStreakOfAnActionScene() {
        val c = MotionComplexityClassifier()
        c.pair(dx = 0.03f * edge)
        c.pair(); c.pair()
        c.pair(dx = 0.03f * edge)                                   // action again: streak restarts
        c.pair(); c.pair()
        assertEquals(MotionComplexity.FAST, c.current)
    }

    @Test fun corruptReadsNeverChangeThePlan() {
        val c = MotionComplexityClassifier()
        c.pair(dx = 0.03f * edge)
        assertEquals(MotionComplexity.FAST, c.onPair(Float.NaN, 0f, 1f, 0f, 0f, 0f, edge))
        assertEquals(MotionComplexity.FAST, c.onPair(0f, 0f, Float.POSITIVE_INFINITY, 0f, 0f, 0f, edge))
        assertEquals(MotionComplexity.FAST, c.onPair(0f, 0f, 1f, 0f, 0f, 0f, 0))
        assertEquals(MotionComplexity.FAST, c.current)
    }

    // ---- plan ----

    private val base = AdaptiveQualityController.LADDER[2]   // Performance: range 10, no subpixel

    @Test fun planLeavesSlowUnknownAndCutAtTheQualityLevel() {
        for (c in listOf(MotionComplexity.SLOW, MotionComplexity.UNKNOWN, MotionComplexity.CUT)) {
            assertEquals(SearchPlan(base.searchRange, base.subpixel), MotionPlan.plan(c, base, true))
        }
    }

    @Test fun staticIsCheaperButKeepsAUsefulRange() {
        val p = MotionPlan.plan(MotionComplexity.STATIC, AdaptiveQualityController.LADDER[0], true)
        assertEquals(MotionPlan.MIN_RANGE, p.searchRange)
        assertFalse(p.subpixel)
        assertTrue(p.searchRange < AdaptiveQualityController.LADDER[0].searchRange)
        // never raises a level that already searches less
        assertEquals(3, MotionPlan.plan(MotionComplexity.STATIC, base.copy(searchRange = 3), true).searchRange)
    }

    @Test fun fastAndComplexSearchWiderButNeverBeyondTheCap() {
        assertEquals(base.searchRange + MotionPlan.FAST_EXTRA_RANGE, MotionPlan.plan(MotionComplexity.FAST, base, true).searchRange)
        val wide = base.copy(searchRange = 15)
        assertEquals(MotionPlan.MAX_RANGE, MotionPlan.plan(MotionComplexity.COMPLEX, wide, true).searchRange)
    }

    @Test fun complexMotionGetsSubpixelOnlyWhenTheGpuHasHeadroom() {
        assertTrue(MotionPlan.plan(MotionComplexity.COMPLEX, base, gpuHeadroom = true).subpixel)
        assertFalse(MotionPlan.plan(MotionComplexity.COMPLEX, base, gpuHeadroom = false).subpixel)
        // FAST keeps the level's own choice
        assertFalse(MotionPlan.plan(MotionComplexity.FAST, base, gpuHeadroom = true).subpixel)
    }

    // ---- target budget ----

    @Test fun onlyTargetsAbove60GetADrawBudget() {
        assertEquals(0f, TargetBudget.drawBudgetMs(30f), 0f)
        assertEquals(0f, TargetBudget.drawBudgetMs(60f), 0f)
        assertEquals(1000f / 90f * 0.6f, TargetBudget.drawBudgetMs(90f), 0.01f)
        assertEquals(1000f / 120f * 0.6f, TargetBudget.drawBudgetMs(120f), 0.01f)
        assertTrue(TargetBudget.drawBudgetMs(120f) < TargetBudget.drawBudgetMs(90f))
    }

    // ---- controller ----

    private fun window(draw: Float, est: Float = 5f, dropped: Int = 0, presented: Int = 90, skipped: Int = 0) = WindowStats(
        durationNs = 1_000_000_000L, sourceFrames = 24, presented = presented, generated = presented * 3 / 4, dropped = dropped,
        avgEstimateMs = est, maxEstimateMs = est, avgDrawMs = draw, skippedEstimates = skipped
    )

    @Test fun theLadderEndsWithTheNewUltraFastRungAndKeepsTheOldIndices() {
        val l = AdaptiveQualityController.LADDER
        assertEquals(listOf("Quality", "Balanced", "Performance", "Minimal", "Ultra Fast"), l.map { it.label })
        assertEquals(listOf(1280, 960, 640, 480, 320), l.map { it.workEdge })
        assertEquals(1, AdaptiveQualityController.indexFor(BoosterQuality.BALANCED))
    }

    @Test fun aSlowDrawStepsDown90ButIsIgnoredAt60() {
        val slow = window(draw = 8f)       // 8 ms: over 60% of an 11.1 ms period, well inside a 16.7 ms one
        val at90 = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        val d90 = at90.onWindow(slow, 24f, 90f, ThermalLevel.NONE)
        assertTrue(d90.changed)
        assertEquals(2, at90.index)

        val at60 = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        assertFalse(at60.onWindow(window(draw = 8f, presented = 60), 24f, 60f, ThermalLevel.NONE).changed)
        assertEquals(1, at60.index)
    }

    @Test fun a120DrawBudgetIsTighterThan90() {
        val w = window(draw = 5.5f, presented = 120)    // fine at 90 (6.7 ms budget), over at 120 (5 ms budget)
        val c90 = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        val c120 = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        assertFalse(c90.onWindow(w.copy(presented = 90), 24f, 90f, ThermalLevel.NONE).changed)
        assertTrue(c120.onWindow(w, 24f, 120f, ThermalLevel.NONE).changed)
    }

    @Test fun aDrawLongerThanTheWholePeriodStepsDownTwiceWithoutWaiting() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        // 14 ms > 11.1 ms period at 90: severe, even with the estimate and drops fine
        c.onWindow(window(draw = 14f), 24f, 90f, ThermalLevel.NONE)
        assertTrue(c.index >= 1)
    }

    @Test fun reachesUltraFastOnlyUnderSustainedPressureAndNeverBelow() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        repeat(40) { c.onWindow(window(draw = 20f, est = 60f, dropped = 40), 24f, 120f, ThermalLevel.NONE) }
        assertEquals(AdaptiveQualityController.LADDER.lastIndex, c.index)
        assertEquals("Ultra Fast", c.level.label)
    }

    @Test fun a90RungIsOnlyRecoveredWhenTheDrawIsWellInsideTheBudget() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false, startIndex = 3)
        // draw 5 ms: under the 6.7 ms budget (not overloaded) but not under 60% of it (4 ms): never "calm"
        repeat(30) { c.onWindow(window(draw = 5f, est = 2f), 24f, 90f, ThermalLevel.NONE) }
        assertEquals(3, c.index)
        // draw 3 ms: calm -> climbs back
        repeat(30) { c.onWindow(window(draw = 3f, est = 2f), 24f, 90f, ThermalLevel.NONE) }
        assertTrue(c.index < 3)
    }

    @Test fun userPresetStillCapsAndManualBehaviourWithoutAdaptiveIsUnchanged() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = false, batterySaver = false)
        repeat(10) { c.onWindow(window(draw = 8f), 24f, 90f, ThermalLevel.NONE) }
        assertEquals(1, c.index)
        val calm = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        repeat(60) { calm.onWindow(window(draw = 1f, est = 1f), 24f, 90f, ThermalLevel.NONE) }
        assertEquals(1, calm.index)     // never above the preset
    }

    // ---- stats window verdict ----

    @Test fun windowReportsTheMostFrequentKnownComplexityIgnoringCutAndUnknown() {
        val acc = StatsAccumulator(windowNs = 1_000_000_000L)
        acc.poll(1L)
        repeat(3) { acc.onComplexity(MotionComplexity.SLOW) }
        repeat(9) { acc.onComplexity(MotionComplexity.CUT) }
        repeat(20) { acc.onComplexity(MotionComplexity.UNKNOWN) }
        repeat(2) { acc.onComplexity(MotionComplexity.FAST) }
        val w = acc.poll(1_000_000_001L)!!
        assertEquals(MotionComplexity.SLOW.ordinal, w.motionComplexity)
        // next window starts clean
        acc.onSourceFrame()
        assertEquals(MotionComplexity.UNKNOWN.ordinal, acc.poll(2_000_000_002L)!!.motionComplexity)
    }

    @Test fun complexityTieGoesToTheHarderClass() {
        val acc = StatsAccumulator(windowNs = 1_000_000_000L)
        acc.poll(1L)
        repeat(2) { acc.onComplexity(MotionComplexity.SLOW) }
        repeat(2) { acc.onComplexity(MotionComplexity.COMPLEX) }
        assertEquals(MotionComplexity.COMPLEX.ordinal, acc.poll(1_000_000_001L)!!.motionComplexity)
    }
}
