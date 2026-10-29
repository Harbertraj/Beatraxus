package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsBoosterPlannerTest {
    private val panel120 = DeviceCaps(listOf(60f, 90f, 120f), glEs3 = true, floatRenderTargets = true)
    private val panel60 = DeviceCaps(listOf(60f), glEs3 = true, floatRenderTargets = true)
    private val panel90 = DeviceCaps(listOf(60f, 90f), glEs3 = true, floatRenderTargets = true)
    private val sdr24 = SourceInfo(24f, 1920, 1080, false)
    private fun on(fps: Int = 60) = FpsBoosterSettings(mode = BoosterMode.ON, targetFps = fps)
    private val auto = FpsBoosterSettings(mode = BoosterMode.AUTO)

    @Test fun offNeverRuns() = assertFalse(FpsBoosterPlanner.plan(FpsBoosterSettings(), panel120, sdr24).active)

    @Test fun hdrNeverRuns() {
        val p = FpsBoosterPlanner.plan(on(), panel120, sdr24.copy(isHdr = true))
        assertFalse(p.active); assertTrue(p.note!!.contains("HDR"))
    }

    @Test fun needsOpenGlEs3() =
        assertFalse(FpsBoosterPlanner.plan(on(), panel120.copy(glEs3 = false), sdr24).active)

    @Test fun onRunsTheRequestedTarget() {
        val p = FpsBoosterPlanner.plan(on(60), panel120, sdr24)
        assertTrue(p.active); assertEquals(60, p.targetFps)
        assertEquals(BoosterEngine.OPTICAL_FLOW, p.engine)
        assertNull(p.note)
    }

    @Test fun targetTheDisplayCannotPaceEvenlyFallsToNextUsable() {
        val p = FpsBoosterPlanner.plan(on(90), panel60, sdr24)
        assertTrue(p.active); assertEquals(60, p.targetFps); assertNotNull(p.note)
        val p45 = FpsBoosterPlanner.plan(on(45), panel60, sdr24)
        assertEquals(30, p45.targetFps)
    }

    @Test fun sourceAlreadyAtTargetIsBypassed() {
        assertFalse(FpsBoosterPlanner.plan(on(60), panel120, SourceInfo(60f, 1920, 1080, false)).active)
        assertFalse(FpsBoosterPlanner.plan(on(60), panel120, SourceInfo(59.94f, 1920, 1080, false)).active)
        assertTrue(FpsBoosterPlanner.plan(on(60), panel120, SourceInfo(30f, 1920, 1080, false)).active)
    }

    @Test fun unknownSourceRateStillRuns() =
        assertTrue(FpsBoosterPlanner.plan(on(60), panel120, SourceInfo(0f, 1920, 1080, false)).active)

    @Test fun engineChoiceRespectsGpuSupport() {
        val noFloat = panel120.copy(floatRenderTargets = false)
        val autoEngine = FpsBoosterPlanner.plan(on(), noFloat, sdr24)
        assertEquals(BoosterEngine.CLASSIC, autoEngine.engine); assertNull(autoEngine.note)
        val explicit = FpsBoosterPlanner.plan(on().copy(engine = BoosterEngine.OPTICAL_FLOW), noFloat, sdr24)
        assertEquals(BoosterEngine.CLASSIC, explicit.engine); assertNotNull(explicit.note)
        assertEquals(BoosterEngine.CLASSIC,
            FpsBoosterPlanner.plan(on().copy(engine = BoosterEngine.CLASSIC), panel120, sdr24).engine)
    }

    @Test fun rifeEngineFallbackWhenModelNotInstalled() {
        val rifePlanUninstalled = FpsBoosterPlanner.plan(
            on().copy(engine = BoosterEngine.AI_RIFE), panel120, sdr24, rifeModelInstalled = false
        )
        assertEquals(BoosterEngine.OPTICAL_FLOW, rifePlanUninstalled.engine)
        assertTrue(rifePlanUninstalled.note!!.contains("RIFE model not installed"))

        val rifePlanInstalled = FpsBoosterPlanner.plan(
            on().copy(engine = BoosterEngine.AI_RIFE), panel120, sdr24, rifeModelInstalled = true
        )
        assertEquals(BoosterEngine.AI_RIFE, rifePlanInstalled.engine)
        assertNull(rifePlanInstalled.note)

        val noFloat = panel120.copy(floatRenderTargets = false)
        val rifeNoFloat = FpsBoosterPlanner.plan(
            on().copy(engine = BoosterEngine.AI_RIFE), noFloat, sdr24, rifeModelInstalled = true
        )
        assertEquals(BoosterEngine.CLASSIC, rifeNoFloat.engine)
        assertTrue(rifeNoFloat.note!!.contains("float textures"))
    }

    @Test fun autoModePicksSixtyForFilmRate() {
        val p = FpsBoosterPlanner.plan(auto, panel120, sdr24)
        assertTrue(p.active); assertEquals(60, p.targetFps)
    }

    @Test fun autoModeLeavesSmoothVideoAlone() {
        assertFalse(FpsBoosterPlanner.plan(auto, panel120, SourceInfo(50f, 1920, 1080, false)).active)
        assertFalse(FpsBoosterPlanner.plan(auto, panel120, SourceInfo(60f, 1920, 1080, false)).active)
    }

    @Test fun autoModeUsesThirtyWhenSixtyCannotBePaced() {
        val p = FpsBoosterPlanner.plan(auto, DeviceCaps(listOf(90f), true, true), sdr24)
        assertTrue(p.active); assertEquals(30, p.targetFps)
        assertFalse("30 -> 30 is no gain",
            FpsBoosterPlanner.plan(auto, DeviceCaps(listOf(90f), true, true), SourceInfo(30f, 1280, 720, false)).active)
    }

    @Test fun batterySaverCapsTargetAndQuality() {
        val p = FpsBoosterPlanner.plan(on(120).copy(batterySaver = true, quality = BoosterQuality.QUALITY), panel120, sdr24)
        assertTrue(p.active); assertEquals(60, p.targetFps)
        assertEquals(BoosterQuality.PERFORMANCE, p.quality); assertNotNull(p.note)
    }

    @Test fun ninetyOnNinetyHzPanel() =
        assertEquals(90, FpsBoosterPlanner.plan(on(90), panel90, sdr24).targetFps)
}
