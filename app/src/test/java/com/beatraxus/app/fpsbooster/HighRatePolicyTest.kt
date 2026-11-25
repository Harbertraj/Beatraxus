package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRatePolicyTest {

    private val v120 = 8_333_333L
    private val v90 = 11_111_111L

    // ---- deadline policy ----

    @Test fun neverShedsAtOrBelow60() {
        val p = FrameDeadlinePolicy()
        repeat(20) { assertTrue(p.shouldGenerate(60f, 5 * v120, v120)) }
        repeat(20) { assertTrue(p.shouldGenerate(30f, 5 * v120, v120)) }
        assertEquals(0L, p.shedTotal)
    }

    @Test fun onTimeFramesAreAlwaysGenerated() {
        val p = FrameDeadlinePolicy()
        repeat(50) { assertTrue(p.shouldGenerate(120f, 0L, v120)) }
        assertTrue(p.shouldGenerate(120f, v120 / 2, v120))     // half a vsync late is still fine
        assertEquals(0L, p.shedTotal)
    }

    @Test fun aFrameAWholeVsyncLateIsShedAndRecoveryNeedsLatenessBelowHalfAVsync() {
        val p = FrameDeadlinePolicy()
        assertFalse(p.shouldGenerate(120f, v120, v120))        // a full vsync behind: cheap frame
        assertFalse(p.shouldGenerate(120f, 3 * v120 / 4, v120)) // improved but still >= half: keep shedding (hysteresis)
        assertTrue(p.shouldGenerate(120f, v120 / 4, v120))      // caught up: generate again
        assertEquals(2L, p.shedTotal)
    }

    @Test fun sustainedLatenessStillGeneratesOneFrameInFive() {
        val p = FrameDeadlinePolicy()
        val results = List(50) { p.shouldGenerate(120f, 2 * v120, v120) }
        val gen = results.count { it }
        assertEquals(50 / (FrameDeadlinePolicy.MAX_CONSECUTIVE_SHED + 1), gen)
        // never more than MAX cheap frames in a row
        var run = 0; var maxRun = 0
        for (r in results) { if (!r) { run++; maxRun = maxOf(maxRun, run) } else run = 0 }
        assertEquals(FrameDeadlinePolicy.MAX_CONSECUTIVE_SHED, maxRun)
    }

    @Test fun resetAndRateChangeForgetShedding() {
        val p = FrameDeadlinePolicy()
        p.shouldGenerate(120f, 2 * v120, v120)
        p.reset()
        assertEquals(0L, p.shedTotal)
        assertTrue(p.shouldGenerate(120f, 0L, v120))
        p.shouldGenerate(120f, 2 * v120, v120)
        assertTrue(p.shouldGenerate(60f, 2 * v120, v120))       // panel dropped to 60: back to the normal policy
        assertTrue(p.shouldGenerate(120f, 0L, v120))
    }

    @Test fun invalidVsyncPeriodNeverSheds() {
        assertTrue(FrameDeadlinePolicy().shouldGenerate(120f, 10_000_000L, 0L))
    }

    /** A shed frame is a real frame, so it can never be counted as generated (no fake FPS). */
    @Test fun shedFramesAreCountedAsRealFramesNotGenerated() {
        val source = LongArray(25) { (it * 1e9 / 24).toLong() }
        val sched = FramePacing.schedule(source, 120)
        val c = FrameClassifier()
        val p = FrameDeadlinePolicy()
        var gen = 0; var shedAll = 0; var shedWouldBeGenerated = 0
        for ((i, f) in sched.withIndex()) {
            val late = if (i in 40..49) 2 * v120 else 0L
            val a = source[f.pair]; val b = source[f.pair + 1]
            val go = f.interpolated && p.shouldGenerate(120f, late, v120)
            val kind = if (go) c.classify(true, f.alpha, a, b) else {
                if (f.interpolated) { shedAll++; if (f.generated) shedWouldBeGenerated++ }
                val n = if (f.alpha < 0.5f) a else b
                c.classify(false, 1f, n, n)
            }
            if (kind == FrameKind.GENERATED) gen++
        }
        assertTrue(shedWouldBeGenerated > 0)
        assertEquals(p.shedTotal.toInt(), shedAll)
        // every frame the schedule wanted generated is generated, except exactly those the deadline policy replaced
        assertEquals(sched.count { it.generated } - shedWouldBeGenerated, gen)
        // and the policy kept at least one generated frame in five even through the late burst
        assertTrue(gen > 0)
    }

    // ---- thermal tracker ----

    @Test fun headroomMapsToLevelsWithEarlierThresholdsForHighRate() {
        assertEquals(ThermalLevel.NONE, ThermalTracker.levelFor(0.5f, false))
        assertEquals(ThermalLevel.NONE, ThermalTracker.levelFor(0.75f, false))
        assertEquals(ThermalLevel.LIGHT, ThermalTracker.levelFor(0.75f, true))
        assertEquals(ThermalLevel.LIGHT, ThermalTracker.levelFor(0.85f, false))
        assertEquals(ThermalLevel.MODERATE, ThermalTracker.levelFor(0.88f, true))
        assertEquals(ThermalLevel.MODERATE, ThermalTracker.levelFor(0.95f, false))
        assertEquals(ThermalLevel.MODERATE, ThermalTracker.levelFor(1.4f, false))   // never SEVERE from a forecast
    }

    @Test fun unavailableHeadroomFallsBackToTheOsStatus() {
        val t = ThermalTracker()
        assertEquals(ThermalLevel.NONE, t.update(ThermalLevel.NONE, Float.NaN, true))
        assertEquals(ThermalLevel.NONE, t.update(ThermalLevel.NONE, -1f, true))
        assertEquals(ThermalLevel.MODERATE, t.update(ThermalLevel.MODERATE, -1f, false))
        assertEquals(ThermalLevel.SEVERE, ThermalTracker().update(ThermalLevel.SEVERE, 0.1f, false))
    }

    @Test fun osStatusAndForecastCombineToTheWorse() {
        assertEquals(ThermalLevel.MODERATE, ThermalTracker().update(ThermalLevel.LIGHT, 0.95f, false))
        assertEquals(ThermalLevel.MODERATE, ThermalTracker().update(ThermalLevel.MODERATE, 0.1f, false))
    }

    @Test fun escalatesAtOnceAndRelaxesOneLevelAfterAStreakWithHysteresis() {
        val t = ThermalTracker()
        assertEquals(ThermalLevel.MODERATE, t.update(ThermalLevel.NONE, 0.95f, false))
        // 0.90 is below 0.92 but inside the hysteresis band (>= 0.87): stays MODERATE however long it lasts
        repeat(10) { assertEquals(ThermalLevel.MODERATE, t.update(ThermalLevel.NONE, 0.90f, false)) }
        // clearly cooler: needs RELAX_READS readings per level
        assertEquals(ThermalLevel.MODERATE, t.update(ThermalLevel.NONE, 0.6f, false))
        assertEquals(ThermalLevel.MODERATE, t.update(ThermalLevel.NONE, 0.6f, false))
        assertEquals(ThermalLevel.LIGHT, t.update(ThermalLevel.NONE, 0.6f, false))
        repeat(2) { assertEquals(ThermalLevel.LIGHT, t.update(ThermalLevel.NONE, 0.6f, false)) }
        assertEquals(ThermalLevel.NONE, t.update(ThermalLevel.NONE, 0.6f, false))
    }

    @Test fun aSingleCoolReadingDoesNotRelax() {
        val t = ThermalTracker()
        t.update(ThermalLevel.NONE, 0.9f, true)
        t.update(ThermalLevel.NONE, 0.3f, true)
        assertEquals(ThermalLevel.MODERATE, t.update(ThermalLevel.NONE, 0.9f, true))
    }

    @Test fun forecastedHeatBlocksUpgradesAndForcesAStepDownInTheController() {
        val tracker = ThermalTracker()
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        val calmWindow = window(draw = 2f, est = 2f)
        val level = tracker.update(ThermalLevel.NONE, 0.9f, true)     // forecast: throttling soon, OS status still None
        assertEquals(ThermalLevel.MODERATE, level)
        c.onWindow(calmWindow, 24f, 120f, level)
        assertEquals(2, c.index)    // stepped down BEFORE any frame was dropped
    }

    // ---- controller at high rate ----

    private fun window(draw: Float, est: Float = 5f, dropped: Int = 0, presented: Int = 120, shed: Int = 0) = WindowStats(
        durationNs = 1_000_000_000L, sourceFrames = 24, presented = presented, generated = presented * 3 / 4, dropped = dropped,
        avgEstimateMs = est, maxEstimateMs = est, avgDrawMs = draw, shed = shed
    )

    @Test fun highRateReactsEveryWindowWhileSixtyKeepsItsCooldown() {
        val fast = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        fast.onWindow(window(draw = 6f), 24f, 120f, ThermalLevel.NONE)    // 6 ms > 5 ms budget
        fast.onWindow(window(draw = 6f), 24f, 120f, ThermalLevel.NONE)
        assertEquals(2, fast.index)                                         // one rung per window

        val slow = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        val drops = window(draw = 3f, dropped = 10, presented = 60)
        slow.onWindow(drops, 24f, 60f, ThermalLevel.NONE)
        slow.onWindow(drops, 24f, 60f, ThermalLevel.NONE)
        assertEquals(1, slow.index)                                         // 60 FPS: a second step waits for the cooldown
    }

    @Test fun aDrawOverTheWholePeriodAtHighRateDropsTwoRungsAtOnce() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        c.onWindow(window(draw = 12f), 24f, 120f, ThermalLevel.NONE)       // 12 ms > 8.3 ms period
        assertEquals(2, c.index)
    }

    @Test fun deadlineSheddingIsLoadAndBlocksRecovery() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        c.onWindow(window(draw = 2f, est = 2f, shed = 20), 24f, 120f, ThermalLevel.NONE)   // 17 % shed, draw fine
        assertEquals(1, c.index)
        val calm = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false, startIndex = 3)
        repeat(40) { calm.onWindow(window(draw = 1f, est = 1f, shed = 1), 24f, 120f, ThermalLevel.NONE) }
        assertEquals(3, calm.index)                                          // any shed frame: not calm, no climb
    }

    @Test fun severeShedAtHighRateDropsTwoRungs() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, adaptive = true, batterySaver = false)
        c.onWindow(window(draw = 2f, est = 2f, shed = 50), 24f, 120f, ThermalLevel.NONE)  // 42 % shed
        assertEquals(2, c.index)
    }

    @Test fun nothingChangesForAHealthy120Window() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, adaptive = true, batterySaver = false)
        repeat(5) { assertFalse(c.onWindow(window(draw = 2f, est = 3f), 24f, 120f, ThermalLevel.NONE).changed) }
        assertEquals(1, c.index)
    }

    // ---- 24 / 30 -> 60 / 90 / 120 schedule matrix with honest accounting ----

    private fun ts(fps: Double, n: Int) = LongArray(n) { (it * 1_000_000_000.0 / fps).toLong() }

    private fun accounting(src: Double, target: Int): Triple<Int, Int, Int> {
        val source = ts(src, (src * 4).toInt() + 1)
        val c = FrameClassifier()
        var gen = 0; var orig = 0; var rep = 0
        for (f in FramePacing.schedule(source, target)) {
            val a = source[f.pair]; val b = source[f.pair + 1]
            val k = if (f.interpolated) c.classify(true, f.alpha, a, b) else { val n = if (f.alpha < 0.5f) a else b; c.classify(false, 1f, n, n) }
            when (k) { FrameKind.GENERATED -> gen++; FrameKind.ORIGINAL -> orig++; FrameKind.REPEATED -> rep++ }
        }
        return Triple(gen, orig, rep)
    }

    @Test fun everyRequiredSourceTargetPairHasTheRightGeneratedShareAndNoRepeats() {
        for (src in listOf(24.0, 30.0)) for (target in listOf(60, 90, 120)) {
            val (gen, orig, rep) = accounting(src, target)
            val total = gen + orig + rep
            val share = gen.toFloat() / total
            assertEquals("$src->$target repeats", 0, rep)
            assertEquals("$src->$target share", FramePacing.generatedShare(src.toFloat(), target), share, 0.02f)
            assertEquals("$src->$target rate", target * 4f, total.toFloat(), target * 0.1f)
        }
    }

    @Test fun exactMultiplesAlignEverySourceFrame() {
        // 24 -> 120 is exactly 5x and 30 -> 120 exactly 4x: one original per source frame, the rest generated
        // (+-1: the clip's last source frame can land on the final tick)
        val (g24, o24, r24) = accounting(24.0, 120)
        assertTrue("o24=$o24", o24 in 96..97); assertTrue("g24=$g24", g24 in 4 * o24 - 4..4 * o24 + 4); assertEquals(0, r24)
        val (g30, o30, r30) = accounting(30.0, 120)
        assertTrue("o30=$o30", o30 in 120..121); assertTrue("g30=$g30", g30 in 3 * o30 - 3..3 * o30 + 3); assertEquals(0, r30)
    }

    // ---- stats ----

    @Test fun shedIsReportedPerWindowAndResets() {
        val acc = StatsAccumulator(windowNs = 1_000_000_000L)
        acc.poll(1L)
        repeat(7) { acc.onPresented(FrameKind.ORIGINAL); acc.onShed() }
        val w = acc.poll(1_000_000_001L)!!
        assertEquals(7, w.shed)
        acc.onSourceFrame()
        assertEquals(0, acc.poll(2_000_000_002L)!!.shed)
    }

    @Test fun missedVsyncsCountOnlyLateFramesNotUnpresentedOnes() {
        val acc = StatsAccumulator(windowNs = 1_000_000_000L)
        acc.poll(1L)
        repeat(36) { acc.onPresented(FrameKind.ORIGINAL) }        // only 36 of 60 frames were presented...
        acc.onMissedVsyncs(3); acc.onMissedVsyncs(1)               // ...but just two of them were actually late
        val w = acc.poll(1_000_000_001L)!!
        assertEquals(4, w.missedVsyncs)                            // not 60 - 36 = 24
        acc.onSourceFrame()
        assertEquals(0, acc.poll(2_000_000_002L)!!.missedVsyncs)
    }

    @Test fun thermalLevelsHaveLabels() {
        assertEquals(listOf("None", "Light", "Moderate", "Severe"), ThermalLevel.entries.map { it.label })
    }
}
