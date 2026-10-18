package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoosterBenchmarkTest {

    private val panel60and120 = listOf(60f, 120f)

    private fun result(
        q: Float? = null, b: Float? = null, p: Float? = null,
        warp: Float = 1f, cpu: Int = 3000, floatOk: Boolean = true
    ): BenchmarkResult {
        val map = HashMap<BoosterQuality, Float>()
        q?.let { map[BoosterQuality.QUALITY] = it }
        b?.let { map[BoosterQuality.BALANCED] = it }
        p?.let { map[BoosterQuality.PERFORMANCE] = it }
        return BenchmarkResult(cpuScore = cpu, gpuScore = 1000, cores = 8, pairMs = map, warpMs = warp, floatRtOk = floatOk, measuredAt = 123L)
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
    }
}
