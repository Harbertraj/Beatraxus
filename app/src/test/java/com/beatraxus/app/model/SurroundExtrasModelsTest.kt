package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundExtrasModelsTest {
    private val on = DspConfig(surroundEnabled = true, surroundHeadphoneMode = true, surroundLayout = SurroundLayout.FIVE_ONE)

    @Test fun headphoneModeOnlyForStereoSourceOnStereoOutput() {
        assertEquals(6, resolveSurroundBinauralChannels(on, 2, false, 2))
        assertEquals(0, resolveSurroundBinauralChannels(on, 2, false, 6))
        assertEquals(0, resolveSurroundBinauralChannels(on, 6, false, 2))
        assertEquals(0, resolveSurroundBinauralChannels(on, 2, true, 2))
        assertEquals(0, resolveSurroundBinauralChannels(on.copy(bitPerfectEnabled = true), 2, false, 2))
        assertEquals(0, resolveSurroundBinauralChannels(on.copy(surroundLayout = SurroundLayout.STEREO), 2, false, 2))
        assertEquals(8, resolveSurroundBinauralChannels(on.copy(surroundLayout = SurroundLayout.AUTO), 2, false, 2))
    }

    @Test fun headphoneModeKeepsStereoDeviceStereoButRealSpeakersStayDiscrete() {
        assertEquals(2, resolveSurroundOutputChannels(on, 2, false, 2))
        assertEquals(6, resolveSurroundOutputChannels(on, 2, false, 8))
    }

    @Test fun distanceDelaysAlignArrival() {
        val d = listOf(2f, 2f, 2f, 1f, 1f, 3f, 3f, 2f)
        val delays = surroundSpeakerDelaysMs(d, true)
        assertEquals(0f, delays[5], 1e-6f)
        assertEquals((3f - 1f) / 343f * 1000f, delays[3], 1e-3f)
        assertEquals(0f, surroundSpeakerDelaysMs(d, false)[5], 1e-6f)
    }

    @Test fun calibrationOffOrEqualMeansNoDelays() {
        assertNull(DspConfig().surroundCalibrationDelaysMsOrNull(true))
        assertNull(DspConfig(surroundCalibrationEnabled = true).surroundCalibrationDelaysMsOrNull(true))
        assertNotNull(DspConfig().withSurroundDistance(3, 1f).surroundCalibrationDelaysMsOrNull(true))
    }

    @Test fun distancesAreSanitizedAndRoundTrip() {
        val s = sanitizeSurroundDistances(listOf(Float.NaN, 99f, 0f))
        assertEquals(8, s.size)
        assertEquals(SURROUND_DISTANCE_DEFAULT_M, s[0], 0f)
        assertEquals(SURROUND_DISTANCE_MAX_M, s[1], 0f)
        assertEquals(SURROUND_DISTANCE_MIN_M, s[2], 0f)
        val d = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        assertEquals(d, deserializeSurroundDistances(serializeSurroundDistances(d)))
        assertNull(deserializeSurroundDistances("1,2"))
    }

    @Test fun presetCodeRoundTrips() {
        val cfg = DspConfig(surroundMode = SurroundMode.entries.last())
        val parsed = parseSurroundPresetCode(surroundPresetCode(cfg))
        assertNotNull(parsed)
        assertEquals(cfg.surroundMode, parsed!!.first)
        assertNull(parseSurroundPresetCode("hello"))
        assertNull(parseSurroundPresetCode("BTXS1|NOPE|0,0,0,0,0,0,0,0"))
        assertEquals(cfg, cfg.withSurroundPresetCode("garbage"))
    }

    @Test fun extraSlotsSaveApplyAndPersist() {
        val cfg = DspConfig().withSurroundSlotSaved("Custom 3")
        assertTrue(cfg.surroundSlotFilled("Custom 3"))
        assertFalse(cfg.surroundSlotFilled("Custom 2"))
        val back = deserializeSurroundSlots(serializeSurroundSlots(cfg.surroundSlotPresets))
        assertEquals(cfg.surroundSlotPresets, back)
        assertEquals(cfg, DspConfig().withSurroundSlotSaved("Not a slot").let { cfg.withSurroundSlotSaved("Not a slot") })
    }

    @Test fun upscalerValuesAreClamped() {
        assertEquals(1f, sanitizeUpscalerAmount(5f), 0f)
        assertEquals(AUDIO_UPSCALER_AMOUNT_DEFAULT, sanitizeUpscalerAmount(Float.NaN), 0f)
        assertEquals(AUDIO_UPSCALER_CUTOFF_MIN_HZ, sanitizeUpscalerCutoff(10f), 0f)
        assertTrue(DspConfig().withAudioUpscalerAmount(0.3f).audioUpscalerEnabled)
    }

    @Test fun everythingNewIsOffByDefault() {
        val c = DspConfig()
        assertFalse(c.surroundHeadphoneMode || c.surroundSmartSteering || c.surroundBassManagement ||
            c.surroundCalibrationEnabled || c.audioUpscalerEnabled || c.surroundHoldOriginal)
        assertEquals(-1, c.surroundTestChannel)
        assertEquals(0f, c.surroundSmartSteeringStrength(), 0f)
    }
}
