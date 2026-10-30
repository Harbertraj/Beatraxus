package com.beatraxus.app.fpsbooster

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

class FpsBoosterManagerTest {
    private val sec = 1_000_000_000L
    private fun win(est: Float = 5f, presented: Int = 30, dropped: Int = 0) =
        WindowStats(sec, 24, presented, 36, dropped, est, est, 2f)

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun updatePlanUpdatesTargetFpsAndResetGraceWindows() {
        var lastStats: BoosterStats? = null
        val listener = object : FpsBoosterManager.Listener {
            override fun onQualityLevel(level: QualityLevel) {}
            override fun onStats(stats: BoosterStats) {
                lastStats = stats
            }
            override fun onFallback(reason: FallbackReason) {}
        }

        val initialPlan = BoosterPlan(
            active = true,
            targetFps = 30,
            engine = BoosterEngine.OPTICAL_FLOW,
            quality = BoosterQuality.BALANCED,
            note = null
        )
        val settings = FpsBoosterSettings(targetFps = 30)
        val mgr = FpsBoosterManager(TestContext(), initialPlan, settings, listOf(60f, 120f), listener)

        mgr.onWindow(win(), sourceFps = 24f, workEdge = 960, addedDelayMs = 25f)
        assertEquals(30, lastStats?.targetFps)

        val newPlan = initialPlan.copy(targetFps = 60)
        mgr.updatePlan(newPlan)

        mgr.onWindow(win(), sourceFps = 24f, workEdge = 960, addedDelayMs = 25f)
        assertEquals(60, lastStats?.targetFps)
    }
}
