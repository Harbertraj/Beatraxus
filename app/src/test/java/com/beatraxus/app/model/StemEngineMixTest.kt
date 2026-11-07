package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gain / mute / solo of the ACTIVE engine, with 4 and 6 stems. */
class StemEngineMixTest {
    private val v = StemChannel.VOCALS.ordinal
    private val d = StemChannel.DRUMS.ordinal
    private val b = StemChannel.BASS.ordinal
    private val g = StemChannel.GUITAR.ordinal
    private val p = StemChannel.PIANO.ordinal
    private val o = StemChannel.OTHER.ordinal

    private fun fast() = DspConfig(stemQuality = StemQuality.FAST)
    private fun max() = DspConfig(stemQuality = StemQuality.MAX)

    private fun linear(c: DspConfig): FloatArray {
        val specs = StemSpecs.forQuality(c.stemQuality)
        return FloatArray(specs.size).also { c.stemLinearGains(specs, it) }
    }

    @Test
    fun gainArraysAreSizedToTheActiveEngine() {
        assertEquals(4, linear(fast()).size)
        assertEquals(6, linear(max()).size)
        assertTrue(linear(fast()).all { it == 1f })
        assertTrue(linear(max()).all { it == 1f })
    }

    @Test
    fun fastReadsVocalsDrumsBassAndTheOtherSlot() {
        val c = fast().withStemGain(v, -6f).withStemGain(o, 6f)
        val gains = linear(c)
        assertEquals(Math.pow(10.0, -6 / 20.0).toFloat(), gains[0], 1e-6f)       // vocals
        assertEquals(1f, gains[1], 0f)                                          // drums
        assertEquals(1f, gains[2], 0f)                                          // bass
        assertEquals(Math.pow(10.0, 6 / 20.0).toFloat(), gains[3], 1e-6f)       // other = slot 5, array index 3
    }

    @Test
    fun maxUsesAllSixSlotsInOrder() {
        val c = max().withStemGain(g, -12f).withStemGain(p, 12f)
        val gains = linear(c)
        assertEquals(Math.pow(10.0, -12 / 20.0).toFloat(), gains[3], 1e-6f)
        assertEquals(Math.pow(10.0, 12 / 20.0).toFloat(), gains[4], 1e-5f)
        assertEquals(1f, gains[5], 0f)
    }

    @Test
    fun muteSilencesOnlyThatStemWith4And6Stems() {
        val f = linear(fast().withStemMuteToggled(d))
        assertEquals(listOf(1f, 0f, 1f, 1f), f.toList())
        val m = linear(max().withStemMuteToggled(g))
        assertEquals(listOf(1f, 1f, 1f, 0f, 1f, 1f), m.toList())
    }

    @Test
    fun soloSilencesTheRestWith4And6Stems() {
        assertEquals(listOf(0f, 1f, 0f, 0f), linear(fast().withStemSoloToggled(d)).toList())
        assertEquals(listOf(0f, 0f, 0f, 1f, 1f, 0f), linear(max().withStemSoloToggled(g).withStemSoloToggled(p)).toList())
    }

    @Test
    fun aSoloLeftOnGuitarDoesNotSilenceTheFourStemEngine() {
        // Solo guitar in Max, then drop back to Fast: Fast has no guitar strip, so the solo must not apply there.
        val soloedInMax = max().copy(stemMixerEnabled = true).withStemSoloToggled(g)
        val backToFast = soloedInMax.copy(stemQuality = StemQuality.FAST)
        assertEquals(listOf(1f, 1f, 1f, 1f), linear(backToFast).toList())
        assertTrue(backToFast.stemMixIsOriginal())
        assertFalse(backToFast.stemMixNeedsStems())
        assertTrue(backToFast.copy(stemQuality = StemQuality.MAX).stemMixNeedsStems())
        // The setting is kept for when the user goes back to Max.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f, 0f), linear(backToFast.copy(stemQuality = StemQuality.MAX)).toList())
    }

    @Test
    fun combinedStripDrivesOtherGuitarAndPianoTogether() {
        val c = max().copy(stemCombineOther = true).withStemGain(o, -6f)
        val gains = linear(c)
        val expected = Math.pow(10.0, -6 / 20.0).toFloat()
        assertEquals(expected, gains[3], 1e-6f)    // guitar follows other
        assertEquals(expected, gains[4], 1e-6f)    // piano follows other
        assertEquals(expected, gains[5], 1e-6f)
        assertEquals(1f, gains[0], 0f)

        val muted = max().copy(stemCombineOther = true).withStemMuteToggled(o)
        assertEquals(listOf(1f, 1f, 1f, 0f, 0f, 0f), linear(muted).toList())

        val soloed = max().copy(stemCombineOther = true).withStemSoloToggled(o)
        assertEquals(listOf(0f, 0f, 0f, 1f, 1f, 1f), linear(soloed).toList())
    }

    @Test
    fun combinedModeIgnoresStaleGuitarAndPianoSettings() {
        val c = max().withStemGain(g, 9f).withStemMuteToggled(p).copy(stemCombineOther = true)
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f, 1f), linear(c).toList())
        assertTrue(c.stemMixIsOriginal())
    }

    @Test
    fun combineToggleDoesNothingForFast() {
        val c = fast().copy(stemCombineOther = true).withStemMuteToggled(o)
        assertEquals(listOf(1f, 1f, 1f, 0f), linear(c).toList())
    }

    @Test
    fun originalMixNeedsNoStems() {
        assertTrue(max().stemMixIsOriginal())
        assertFalse(DspConfig(stemMixerEnabled = true).stemMixNeedsStems())
        assertTrue(DspConfig(stemMixerEnabled = true).withStemMuteToggled(v).stemMixNeedsStems())
        assertFalse(DspConfig(stemMixerEnabled = false).withStemMuteToggled(v).stemMixNeedsStems())
        assertTrue(max().copy(stemMixerEnabled = true).withStemGain(p, 3f).stemMixNeedsStems())
        // A level change on a stem the Fast engine does not have changes nothing there.
        assertFalse(fast().copy(stemMixerEnabled = true).withStemGain(p, 3f).stemMixNeedsStems())
    }

    @Test
    fun engineGainsKeepTheSixSlotShape() {
        val db = max().withStemMuteToggled(b).stemEngineGainsDb()
        assertEquals(STEM_COUNT, db.size)
        assertNull(db[b])
        assertEquals(0f, db[v]!!, 0f)
        // Fast: slots the engine does not have are null (never read).
        val fastDb = fast().stemEngineGainsDb()
        assertNull(fastDb[g]); assertNull(fastDb[p])
        assertEquals(0f, fastDb[o]!!, 0f)
    }

    @Test
    fun controlMaskMatchesTheStrips() {
        assertEquals(0b100111, stemControlMask(StemQuality.FAST, false))   // vocals, drums, bass, other
        assertEquals(0b111111, stemControlMask(StemQuality.MAX, false))
        assertEquals(0b100111, stemControlMask(StemQuality.MAX, true))
    }

    @Test
    fun savedGainsSurviveTheNewStemsAndOldFourValueStringsMigrate() {
        // Six values (what the current build stores) round-trip untouched.
        val six = listOf(-3f, 1f, 2f, 0.5f, -1f, 4f)
        assertEquals(six, deserializeStemGains(serializeStemGains(six)))
        // A 4 value string (Vocals, Drums, Bass, Other) keeps its numbers; Other moves to its slot, new stems at 0 dB.
        assertEquals(listOf(-3f, 1f, 2f, 0f, 0f, 4f), deserializeStemGains("-3.0,1.0,2.0,4.0"))
        // Garbage and missing values fall back to defaults.
        assertEquals(List(STEM_COUNT) { 0f }, deserializeStemGains("a,b,c,d"))
        assertEquals(List(STEM_COUNT) { 0f }, deserializeStemGains(null))
    }
}
