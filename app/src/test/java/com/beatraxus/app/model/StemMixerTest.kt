package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemMixerTest {

    private val vocals = StemChannel.VOCALS.ordinal
    private val drums = StemChannel.DRUMS.ordinal
    private val bass = StemChannel.BASS.ordinal

    @Test
    fun thereAreSixStemsInTheOrderTheScreenShows() {
        assertEquals(STEM_COUNT, StemChannel.entries.size)
        assertEquals(listOf("Vocals", "Drums", "Bass", "Guitar", "Piano", "Other"), StemChannel.entries.map { it.label })
    }

    @Test
    fun defaultsPlayEverythingUntouched() {
        val c = DspConfig()
        assertFalse(c.stemMixerEnabled)
        assertEquals(List(STEM_COUNT) { 0f }, sanitizeStemGains(c.stemGainsDb))
        assertEquals(List(STEM_COUNT) { 0f }, c.stemEffectiveGainsDb())
        assertEquals("Original", c.activeStemMix())
    }

    @Test
    fun gainsAreSanitisedToSixFiniteSnappedValues() {
        assertEquals(List(STEM_COUNT) { 0f }, sanitizeStemGains(null))
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f), sanitizeStemGains(listOf(Float.NaN, Float.POSITIVE_INFINITY)))
        val g = sanitizeStemGains(listOf(99f, -99f, 1.26f))
        assertEquals(STEM_GAIN_MAX_DB, g[0], 0f)
        assertEquals(STEM_GAIN_MIN_DB, g[1], 0f)
        assertEquals(1.5f, g[2], 0f)
        assertEquals(STEM_COUNT, g.size)
    }

    @Test
    fun savedFormRoundTrips() {
        val gains = listOf(-3f, 0f, 2.5f, 0f, 12f, -12f)
        assertEquals(gains, deserializeStemGains(serializeStemGains(gains)))
        assertEquals(List(STEM_COUNT) { 0f }, deserializeStemGains(null))
        assertEquals(List(STEM_COUNT) { 0f }, deserializeStemGains("junk,,x"))
    }

    @Test
    fun setGainChangesOnlyThatStem() {
        val c = DspConfig().withStemGain(bass, 4f)
        assertEquals(4f, c.stemGainsDb[bass], 0f)
        assertEquals(0f, c.stemGainsDb[vocals], 0f)
        assertEquals(c, c.withStemGain(-1, 3f))
        assertEquals(c, c.withStemGain(STEM_COUNT, 3f))
        assertEquals(STEM_GAIN_MAX_DB, DspConfig().withStemGain(drums, 50f).stemGainsDb[drums], 0f)
    }

    @Test
    fun muteSilencesOneStemAndKeepsTheRest() {
        val c = DspConfig().withStemGain(drums, -3f).withStemMuteToggled(vocals)
        val out = c.stemEffectiveGainsDb()
        assertNull(out[vocals])
        assertEquals(-3f, out[drums]!!, 0f)
        assertEquals(0f, out[bass]!!, 0f)
        assertEquals(0f, c.withStemMuteToggled(vocals).stemEffectiveGainsDb()[vocals]!!, 0f)
    }

    @Test
    fun soloSilencesEveryOtherStem() {
        val out = DspConfig().withStemSoloToggled(drums).stemEffectiveGainsDb()
        assertEquals(0f, out[drums]!!, 0f)
        assertTrue(out.filterIndexed { i, _ -> i != drums }.all { it == null })
        val two = DspConfig().withStemSoloToggled(drums).withStemSoloToggled(bass).stemEffectiveGainsDb()
        assertTrue(two[drums] != null && two[bass] != null && two[vocals] == null)
    }

    @Test
    fun muteAndSoloNeverStickTogetherOnOneStem() {
        val muted = DspConfig().withStemMuteToggled(vocals).withStemSoloToggled(vocals)
        assertEquals(0, muted.stemMuteMask)
        assertEquals(1 shl vocals, muted.stemSoloMask)
        val soloed = DspConfig().withStemSoloToggled(vocals).withStemMuteToggled(vocals)
        assertEquals(0, soloed.stemSoloMask)
        assertEquals(1 shl vocals, soloed.stemMuteMask)
    }

    @Test
    fun quickMixesSetMasksAndResetLevels() {
        val base = DspConfig(stemMixerEnabled = true).withStemGain(bass, 6f)
        val karaoke = base.withStemMixPreset("Karaoke")
        assertEquals("Karaoke", karaoke.activeStemMix())
        assertNull(karaoke.stemEffectiveGainsDb()[vocals])
        assertEquals(0f, karaoke.stemGainsDb[bass], 0f)
        assertTrue(karaoke.stemMixerEnabled)

        val acapella = base.withStemMixPreset("Acapella")
        assertEquals("Acapella", acapella.activeStemMix())
        assertEquals(0f, acapella.stemEffectiveGainsDb()[vocals]!!, 0f)

        val rhythm = base.withStemMixPreset("Rhythm only").stemEffectiveGainsDb()
        assertTrue(rhythm[drums] != null && rhythm[bass] != null && rhythm[vocals] == null)

        assertEquals(base, base.withStemMixPreset("No such mix"))
    }

    @Test
    fun everyQuickMixIsUniqueAndSelfDescribing() {
        val names = BUILT_IN_STEM_MIXES.map { it.name }
        assertEquals(names.size, names.toSet().size)
        BUILT_IN_STEM_MIXES.forEach {
            assertTrue(it.description.isNotBlank())
            assertEquals(it.description, stemMixDescription(it.name))
            assertEquals(it.name, DspConfig().withStemMixPreset(it.name).activeStemMix())
        }
        assertEquals("Your own mix.", stemMixDescription(null))
    }

    @Test
    fun aHandMadeMixHighlightsNoQuickMix() {
        assertNull(DspConfig().withStemGain(drums, 1f).activeStemMix())
        assertNull(DspConfig().withStemMuteToggled(bass).activeStemMix())
    }

    @Test
    fun resetKeepsTheSwitchButClearsTheMix() {
        val c = DspConfig(stemMixerEnabled = true)
            .withStemGain(vocals, 5f).withStemMuteToggled(drums).withStemSoloToggled(bass)
            .withStemMixReset()
        assertTrue(c.stemMixerEnabled)
        assertEquals("Original", c.activeStemMix())
        assertEquals(0, c.stemMuteMask)
        assertEquals(0, c.stemSoloMask)
    }

    @Test
    fun strayMaskBitsAreIgnored() {
        val c = DspConfig(stemMuteMask = 0xFFFF_FFC0.toInt(), stemSoloMask = 0x7000)
        assertEquals(List(STEM_COUNT) { 0f }, c.stemEffectiveGainsDb())
        assertEquals("Original", c.activeStemMix())
    }
}
