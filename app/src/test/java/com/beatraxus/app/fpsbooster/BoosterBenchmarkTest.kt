package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoosterBenchmarkTest {

    private val panel60and120 = listOf(60f, 120f)

    private fun result(
        q: Float? = null, b: Float? = null, p: Float? = null,
        warp: Float = 1f, cpu: Int = 3000, floatOk: Boolean = true, fuse: Float = 1.5f
    ): BenchmarkResult {
        val map = HashMap<BoosterQuality, Float>()
        q?.let { map[BoosterQuality.QUALITY] = it }
        b?.let { map[BoosterQuality.BALANCED] = it }
        p?.let { map[BoosterQuality.PERFORMANCE] = it }
        return BenchmarkResult(cpuScore = cpu, gpuScore = 1000, cores = 8, pairMs = map, warpMs = warp, floatRtOk = floatOk, fuseMs = fuse, measuredAt = 123L)
    }

    @Test fun fastDeviceGetsQualityAndTheHighestUsableTarget() {
        val r = BoosterRecommender.recommend(result(q = 8f, b = 5f, p = 3f, warp = 1f), panel60and120)
        assertEquals(BoosterEngine.OPTICAL_FLOW, r.engine)
        assertEquals(BoosterQuality.QUALITY, r.quality)
        assertEquals(120, r.targetFps)
        assertEquals(DeviceTier.HIGH, r.tier)
    }

    @Test fun midDeviceDropsToBalancedAndLowerTarget() {
        val r = BoosterRecommender.recommend(result(q = 25f, b = 12f, p = 7f, warp = 3f), panel60and120)
        assertEquals(BoosterEngine.OPTICAL_FLOW, r.engine)
        assertEquals(BoosterQuality.BALANCED, r.quality)
        assertEquals(60, r.targetFps)
        assertEquals(DeviceTier.MID, r.tier)
    }

    @Test fun slowDeviceKeepsOpticalFlowOnPerformanceAt30() {
        val r = BoosterRecommender.recommend(result(q = 50f, b = 30f, p = 15f, warp = 6f), panel60and120)
        assertEquals(BoosterEngine.OPTICAL_FLOW, r.engine)
        assertEquals(BoosterQuality.PERFORMANCE, r.quality)
        assertEquals(30, r.targetFps)
        assertEquals(DeviceTier.LOW, r.tier)
    }

    @Test fun tooSlowForOpticalFlowFallsBackToClassic() {
        val r = BoosterRecommender.recommend(result(p = 40f, warp = 6f), panel60and120)
        assertEquals(BoosterEngine.CLASSIC, r.engine)
        assertEquals(BoosterQuality.PERFORMANCE, r.quality)
        assertEquals(60, r.targetFps)
    }

    @Test fun noFloatRenderTargetsMeansClassic() {
        val r = BoosterRecommender.recommend(result(floatOk = false, warp = 1f), panel60and120)
        assertEquals(BoosterEngine.CLASSIC, r.engine)
        assertTrue(r.summary.contains("cannot render"))
    }

    @Test fun targetsOnlyUseRatesThePanelCanPace() {
        // A 60 Hz panel cannot show 90 or 120 evenly, however fast the GPU is.
        val r = BoosterRecommender.recommend(result(q = 8f, b = 5f, p = 3f, warp = 1f), listOf(60f))
        assertEquals(60, r.targetFps)
        // A 90 Hz panel paces 45 and 90.
        val r90 = BoosterRecommender.recommend(result(q = 8f, b = 5f, p = 3f, warp = 1f), listOf(60f, 90f))
        assertEquals(90, r90.targetFps)
    }

    @Test fun weakCpuCapsTheTargetAt60() {
        val r = BoosterRecommender.recommend(result(q = 8f, b = 5f, p = 3f, warp = 1f, cpu = 300), panel60and120)
        assertEquals(60, r.targetFps)
        assertTrue(r.summary.contains("CPU"))
    }

    @Test fun applyKeepsModeAndToggles() {
        val rec = BoosterRecommender.recommend(result(q = 8f, b = 5f, p = 3f), panel60and120)
        val s = FpsBoosterSettings(mode = BoosterMode.AUTO, batterySaver = true, adaptiveResolution = false, showStats = false)
        val out = rec.applyTo(s)
        assertEquals(BoosterMode.AUTO, out.mode)
        assertTrue(out.batterySaver)
        assertEquals(false, out.adaptiveResolution)
        assertEquals(rec.engine, out.engine)
        assertEquals(rec.quality, out.quality)
        assertEquals(rec.targetFps, out.targetFps)
    }

    @Test fun storedFormRoundTrips() {
        val r = result(q = 8.25f, b = null, p = 3.5f, warp = 1.5f, cpu = 1234)
        val back = BenchmarkResult.fromStored(r.toStored())
        assertEquals(r, back)
    }

    @Test fun garbageOrOldVersionIsIgnored() {
        assertNull(BenchmarkResult.fromStored(null))
        assertNull(BenchmarkResult.fromStored(""))
        assertNull(BenchmarkResult.fromStored("nonsense"))
        assertNull(BenchmarkResult.fromStored("99|1|1|1|-,-,-|1.0|1|0"))
        assertNull(BenchmarkResult.fromStored("1|x|1|1|-,-,-|1.0|1|0"))
        assertNull(BenchmarkResult.fromStored("2|x|1|1|-,-,-|1.0|1|0|1.0"))
    }

    @Test fun resultFromBeforeTheFusionTestIsDiscarded() {
        // version 1 (no fusion timing, 8 fields): must be re-measured so the AI gate has data
        assertNull(BenchmarkResult.fromStored("1|3000|1000|8|-,-,5.0|1.0|1|123"))
    }

    @Test fun fusionTimeSurvivesStorage() {
        val back = BenchmarkResult.fromStored(result(b = 5f, p = 3f, fuse = 2.25f).toStored())!!
        assertEquals(2.25f, back.fuseMs, 0f)
        val none = BenchmarkResult.fromStored(result(b = 5f, p = 3f, fuse = BenchmarkResult.FUSE_NOT_MEASURED).toStored())!!
        assertEquals(BenchmarkResult.FUSE_NOT_MEASURED, none.fuseMs, 0f)
    }

    // ---- AI fusion gate ----

    @Test fun fusionAllowedWhenTheBudgetHolds() {
        // 30 * 5 + 60 * (1 + 1.5) = 300 ms/s <= 650
        val r = result(b = 5f, p = 3f)
        assertEquals(AiFusionVerdict.OK, BoosterRecommender.aiFusionVerdict(r, BoosterQuality.BALANCED, 60))
    }

    @Test fun fusionNeedsADeviceTest() {
        assertEquals(AiFusionVerdict.NOT_TESTED, BoosterRecommender.aiFusionVerdict(null, BoosterQuality.BALANCED, 60))
    }

    @Test fun fusionUnsupportedWithoutTwoFloatTargets() {
        val r = result(b = 5f, fuse = BenchmarkResult.FUSE_NOT_MEASURED)
        assertEquals(AiFusionVerdict.UNSUPPORTED, BoosterRecommender.aiFusionVerdict(r, BoosterQuality.BALANCED, 60))
        assertEquals(AiFusionVerdict.UNSUPPORTED,
            BoosterRecommender.aiFusionVerdict(result(b = 5f, floatOk = false), BoosterQuality.BALANCED, 60))
    }

    @Test fun fusionTooSlowOnASinglePassOrOnTheBudget() {
        assertEquals(AiFusionVerdict.TOO_SLOW,
            BoosterRecommender.aiFusionVerdict(result(b = 5f, fuse = 4.5f), BoosterQuality.BALANCED, 60))
        // 30 * 10 + 120 * (3 + 3.5) = 1080 ms/s > 650, although each part alone is fine
        val heavy = result(b = 10f, warp = 3f, fuse = 3.5f)
        assertEquals(AiFusionVerdict.TOO_SLOW, BoosterRecommender.aiFusionVerdict(heavy, BoosterQuality.BALANCED, 120))
        // the same device is fine at a lower target: 300 + 45 * 6.5 = 592.5
        assertEquals(AiFusionVerdict.OK, BoosterRecommender.aiFusionVerdict(heavy, BoosterQuality.BALANCED, 45))
    }

    @Test fun fusionTooSlowForAPresetThatWasNotMeasured() {
        assertEquals(AiFusionVerdict.TOO_SLOW,
            BoosterRecommender.aiFusionVerdict(result(p = 3f), BoosterQuality.QUALITY, 60))
    }

    @Test fun fusionOnlyRunsWhenEverythingAgrees() {
        val done = BenchmarkState.Done(result(b = 5f, p = 3f), BoosterRecommender.recommend(result(b = 5f, p = 3f), panel60and120))
        val on = FpsBoosterSettings(mode = BoosterMode.ON, aiFusion = true)
        val plan = BoosterPlan(true, 60, BoosterEngine.OPTICAL_FLOW, BoosterQuality.BALANCED, null)
        assertTrue(BoosterRecommender.aiFusionEnabled(on, plan, done))
        assertTrue(!BoosterRecommender.aiFusionEnabled(on.copy(aiFusion = false), plan, done))
        assertTrue(!BoosterRecommender.aiFusionEnabled(on.copy(batterySaver = true), plan, done))
        assertTrue(!BoosterRecommender.aiFusionEnabled(on, plan.copy(engine = BoosterEngine.CLASSIC), done))
        assertTrue(!BoosterRecommender.aiFusionEnabled(on, plan.copy(engine = BoosterEngine.AI_RIFE), done))
        assertTrue(!BoosterRecommender.aiFusionEnabled(on, plan.copy(active = false), done))
        assertTrue(!BoosterRecommender.aiFusionEnabled(on, plan, BenchmarkState.Idle))
    }
}
