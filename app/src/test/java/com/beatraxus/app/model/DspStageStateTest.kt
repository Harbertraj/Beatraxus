package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspStageStateTest {

    @Test
    fun defaultConfigHasNothingOn() {
        val config = DspConfig()
        assertEquals(List(5) { DspStageState.OFF }, config.stageStates())
        assertFalse(config.anyDspStageOn())
    }

    @Test
    fun anyStudioPageSwitchedOnCountsAsOn() {
        val oneOnEachPage = listOf(
            DspConfig(eqEnabled = true),
            DspConfig(aiEqEnabled = true),
            DspConfig(surroundEnabled = true),
            DspConfig(bassEnabled = true),
            DspConfig(stereoExpansionEnabled = true),
            DspConfig(reverbEnabled = true),
            DspConfig(stemMixerEnabled = true)
        )
        for (config in oneOnEachPage) assertTrue(config.anyDspStageOn())
    }

    @Test
    fun pagesAreReportedInStudioOrder() {
        val states = DspConfig(surroundEnabled = true, reverbEnabled = true).stageStates()
        assertEquals(
            listOf(DspStageState.OFF, DspStageState.ON, DspStageState.OFF, DspStageState.ON, DspStageState.OFF),
            states
        )
    }

    @Test
    fun stemsIsTheFifthPageAndSpatialNoLongerLightsIt() {
        assertEquals(DspStageState.ON, DspConfig(stemMixerEnabled = true).stageStates()[4])
        assertEquals(DspStageState.OFF, DspConfig(spatialAudioEnabled = true, audio3DStageEnabled = true).stageStates()[4])
        // Bit-Perfect passes the song through untouched, so it bypasses the stem mixer too.
        assertEquals(DspStageState.BYPASS, DspConfig(stemMixerEnabled = true, bitPerfectEnabled = true).stageStates()[4])
    }

    @Test
    fun bypassedPagesDoNotCountAsOn() {
        // A/B bypass switches the whole chain off.
        assertFalse(DspConfig(eqEnabled = true, reverbEnabled = true, bypassAll = true).anyDspStageOn())
        // Bit-Perfect bypasses EQ unless it is explicitly un-bypassed.
        assertFalse(DspConfig(eqEnabled = true, bitPerfectEnabled = true).anyDspStageOn())
        assertTrue(DspConfig(eqEnabled = true, bitPerfectEnabled = true, bitPerfectUnbypassEq = true).anyDspStageOn())
    }

    @Test
    fun settingsThatAreOnByDefaultDoNotFillTheIcon() {
        // Direct volume control and "no headroom gain" are on out of the box; they are not Studio pages.
        val config = DspConfig(dvcEnabled = true, noHeadroomGainEnabled = true)
        assertFalse(config.anyDspStageOn())
    }
}
