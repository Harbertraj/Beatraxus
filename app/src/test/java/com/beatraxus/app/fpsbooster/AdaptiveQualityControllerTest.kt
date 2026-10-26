package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveQualityControllerTest {
    private val sec = 1_000_000_000L
    private fun win(est: Float = 5f, presented: Int = 60, dropped: Int = 0) =
        WindowStats(sec, 24, presented, 36, dropped, est, est, 2f)

    private fun AdaptiveQualityController.feed(w: WindowStats, thermal: ThermalLevel = ThermalLevel.NONE) =
        onWindow(w, 24f, 60f, thermal)

    private val overload = win(est = 60f)   // budget at 24 fps = 29 ms
    private val calm = win(est = 5f)

    @Test fun startsAtTheUsersPreset() {
        assertEquals(0, AdaptiveQualityController(BoosterQuality.QUALITY, true, false).index)
        assertEquals(1, AdaptiveQualityController(BoosterQuality.BALANCED, true, false).index)
        assertEquals(2, AdaptiveQualityController(BoosterQuality.PERFORMANCE, true, false).index)
        assertEquals(2, AdaptiveQualityController(BoosterQuality.QUALITY, true, true).index)
    }

    @Test fun ladderMatchesThePresets() {
        BoosterQuality.entries.forEach { q ->
            val l = AdaptiveQualityController.LADDER[AdaptiveQualityController.indexFor(q)]
            assertEquals(q.workEdge, l.workEdge)
            assertEquals(q.frameEdge, l.frameEdge)
            assertEquals(q.searchRange, l.searchRange)
            assertEquals(q.subpixel, l.subpixel)
        }
    }

    @Test fun calmWindowsNeverChangeAnything() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        repeat(50) {
            val d = c.feed(calm)
            assertTrue(!d.changed); assertNull(d.fallback)
        }
        assertEquals(1, c.index)
    }

    @Test fun overloadStepsDownAtMostOnceEveryCooldown() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        c.feed(overload); assertEquals(2, c.index)
        c.feed(overload); assertEquals(2, c.index)   // cooling down
        c.feed(overload); assertEquals(3, c.index)
    }

    @Test fun droppedFramesCountAsOverloadEvenWhenEstimateIsFast() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        c.feed(win(est = 5f, presented = 50, dropped = 10))
        assertEquals(2, c.index)
    }

    @Test fun neverSwitchesOffBecauseOfLoad() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        repeat(60) { assertNull(c.feed(overload).fallback) }
        assertEquals("keeps running at the lowest rung", AdaptiveQualityController.LADDER.lastIndex, c.index)
    }

    @Test fun withoutAdaptiveItKeepsRunningAtTheChosenQuality() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, false, false)
        repeat(30) { assertNull(c.feed(overload).fallback) }
        assertEquals(1, c.index)
    }

    @Test fun sixtyFpsSourceOverloadStillDoesNotFallBack() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, true, false)
        repeat(40) { assertNull(c.onWindow(win(est = 30f, presented = 70), 60f, 120f, ThermalLevel.NONE).fallback) }
    }

    @Test fun aSingleBadWindowDoesNotStopTheBooster() {
        val c = AdaptiveQualityController(BoosterQuality.PERFORMANCE, true, false)
        assertNull(c.feed(overload).fallback)
        repeat(10) { assertNull(c.feed(calm).fallback) }
    }

    @Test fun climbsBackAfterSustainedHeadroom() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        c.feed(overload); assertEquals(2, c.index)
        repeat(AdaptiveQualityController.UPGRADE_AFTER - 1) { c.feed(calm); assertEquals(2, c.index) }
        c.feed(calm)
        assertEquals(1, c.index)
        repeat(30) { c.feed(calm) }
        assertEquals("never above the user's preset", 1, c.index)
    }

    @Test fun doesNotOscillateAfterAFailedUpgrade() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        c.feed(overload)
        repeat(AdaptiveQualityController.UPGRADE_AFTER) { c.feed(calm) }
        assertEquals(1, c.index)                       // upgraded
        c.feed(overload); c.feed(overload)              // upgrade did not hold
        assertEquals(2, c.index)
        repeat(20) { c.feed(calm) }
        assertEquals("upgrades are blocked for a while", 2, c.index)
    }

    @Test fun moderateThermalForcesOneStepDownAndBlocksUpgrades() {
        val c = AdaptiveQualityController(BoosterQuality.BALANCED, true, false)
        c.feed(calm, ThermalLevel.MODERATE)
        assertEquals(2, c.index)
        repeat(30) { c.feed(calm, ThermalLevel.MODERATE) }
        assertEquals(2, c.index)
    }

    @Test fun severeThermalFallsBackImmediately() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, true, false)
        assertEquals(FallbackReason.THERMAL, c.feed(calm, ThermalLevel.SEVERE).fallback)
    }

    @Test fun batterySaverNeverRaisesQuality() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, true, true)
        repeat(60) { c.feed(calm) }
        assertEquals(2, c.index)
    }

    @Test fun thermalStatusMapping() {
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromAndroidStatus(0))
        assertEquals(ThermalLevel.LIGHT, ThermalLevel.fromAndroidStatus(1))
        assertEquals(ThermalLevel.MODERATE, ThermalLevel.fromAndroidStatus(2))
        assertEquals(ThermalLevel.SEVERE, ThermalLevel.fromAndroidStatus(3))
        assertEquals(ThermalLevel.SEVERE, ThermalLevel.fromAndroidStatus(6))
    }

    @Test fun skippedEstimatesCountAsOverload() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, true, false)
        // estimates are fast but 3 of 24 source frames (12 %) could not get one: the GPU is saturated
        val w = WindowStats(sec, 24, 60, 36, 0, 5f, 5f, 2f, skippedEstimates = 3)
        val d = c.feed(w)
        assertTrue("steps down", d.changed)
        assertEquals(1, c.index)
    }

    @Test fun fewSkipsDoNotChangeQualityButBlockUpgrade() {
        val c = AdaptiveQualityController(BoosterQuality.QUALITY, true, false)
        c.feed(overload); c.feed(calm); c.feed(calm)   // now at Balanced
        val at = c.index
        repeat(AdaptiveQualityController.UPGRADE_AFTER + 2) {
            c.feed(WindowStats(sec, 24, 60, 36, 0, 5f, 5f, 2f, skippedEstimates = 1))
        }
        assertEquals("a skip is not calm", at, c.index)
    }
}
