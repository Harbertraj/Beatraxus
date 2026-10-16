package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsBoosterSettingsTest {
    @Test fun defaultsAreOffAndSafe() {
        val s = FpsBoosterSettings()
        assertEquals(BoosterMode.OFF, s.mode); assertTrue(!s.isOn)
        assertEquals(BoosterEngine.AUTO, s.engine); assertEquals(BoosterQuality.PERFORMANCE, s.quality)
        assertTrue(s.adaptiveResolution); assertTrue(!s.batterySaver)
    }

    @Test fun corruptStoredValuesFallBackToDefaults() {
        val s = FpsBoosterSettings.fromStored("???", 77, null, "ULTRA", null, null, null)
        assertEquals(FpsBoosterSettings(), s)
    }

    @Test fun validStoredValuesRoundTrip() {
        val s = FpsBoosterSettings.fromStored("AUTO", 90, "CLASSIC", "QUALITY", false, true, false)
        assertEquals(BoosterMode.AUTO, s.mode); assertEquals(90, s.targetFps)
        assertEquals(BoosterEngine.CLASSIC, s.engine); assertEquals(BoosterQuality.QUALITY, s.quality)
        assertTrue(!s.adaptiveResolution); assertTrue(s.batterySaver); assertTrue(!s.showStats)
    }

    @Test fun targetsAreTheDocumentedSet() {
        assertEquals(listOf(30, 45, 60, 90, 120), BoosterTargets.ALL)
        assertEquals(60, BoosterTargets.sanitize(59)); assertEquals(120, BoosterTargets.sanitize(120))
    }

    @Test fun aiEngineIsNotOffered() {
        assertTrue(BoosterEngine.entries.none { it.name.contains("AI") })
    }

    @Test fun legacyPreferenceMigration() {
        assertNull(FpsBoosterSettings.migrateLegacy("ORIGINAL", false))
        assertNull(FpsBoosterSettings.migrateLegacy(null, true))
        val m = FpsBoosterSettings.migrateLegacy("FPS_90", true)!!
        assertEquals(BoosterMode.ON, m.mode); assertEquals(90, m.targetFps)
        assertEquals(BoosterQuality.PERFORMANCE, m.quality)
        assertEquals(BoosterQuality.PERFORMANCE, FpsBoosterSettings.migrateLegacy("FPS_60", false)!!.quality)
    }

    @Test fun qualityPresetsAreOrdered() {
        val e = BoosterQuality.entries
        assertTrue(e.zipWithNext().all { (a, b) -> a.workEdge < b.workEdge })
    }
}
