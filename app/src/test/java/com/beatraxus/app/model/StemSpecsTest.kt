package com.beatraxus.app.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StemSpecsTest {

    @Test
    fun fastHasFourStemsAndMaxHasSixInTheDocumentedOrder() {
        assertEquals(listOf("vocals", "drums", "bass", "other"), StemSpecs.FAST.map { it.key })
        assertEquals(listOf("vocals", "drums", "bass", "guitar", "piano", "other"), StemSpecs.MAX.map { it.key })
        assertEquals(listOf("Vocals", "Drums", "Bass", "Guitar", "Piano", "Other"), StemSpecs.MAX.map { it.label })
        assertEquals(StemSpecs.FAST, StemSpecs.forQuality(StemQuality.FAST))
        assertEquals(StemSpecs.MAX, StemSpecs.forQuality(StemQuality.MAX))
    }

    @Test
    fun maxOrderIsTheScreenOrderOfTheSixChannels() {
        assertEquals(StemChannel.entries, StemSpecs.MAX.map { it.channel })
        assertEquals(StemSpecs.MAX.size, STEM_COUNT)
    }

    @Test
    fun stemOrderMappingPerEngine() {
        // SCNet model output order is drums, bass, other, vocals; the app wants vocals, drums, bass, other.
        assertArrayEquals(intArrayOf(3, 0, 1, 2), StemSpecs.appToModelSources(StemSpecs.FAST, StemSpecs.SCNET_MODEL_ORDER))
        // htdemucs_6s output order is drums, bass, other, vocals, guitar, piano.
        assertArrayEquals(intArrayOf(3, 0, 1, 4, 5, 2), StemSpecs.appToModelSources(StemSpecs.MAX, StemSpecs.HTDEMUCS_6S_MODEL_ORDER))
    }

    @Test
    fun everyAppStemIsFoundInItsModelAndNoModelSourceIsUsedTwice() {
        for ((specs, order) in listOf(
            StemSpecs.FAST to StemSpecs.SCNET_MODEL_ORDER,
            StemSpecs.MAX to StemSpecs.HTDEMUCS_6S_MODEL_ORDER
        )) {
            val map = StemSpecs.appToModelSources(specs, order)
            assertTrue(map.all { it >= 0 })
            assertEquals(map.size, map.toSet().size)
        }
        assertEquals(-1, StemSpecs.appToModelSources(StemSpecs.MAX, StemSpecs.SCNET_MODEL_ORDER)[3]) // guitar: not in SCNet
    }

    @Test
    fun namesFilesAndColoursAreUnique() {
        assertEquals(6, StemSpecs.MAX.map { it.fileName }.toSet().size)
        assertEquals(6, StemSpecs.MAX.map { it.colorArgb }.toSet().size)
        assertEquals("guitar.pcm", StemSpecs.MAX[3].fileName)
        // The Fast engine shares the spec (and colour) of the same stem in Max.
        StemSpecs.FAST.forEach { f -> assertEquals(f, StemSpecs.MAX.first { it.key == f.key }) }
    }

    @Test
    fun qualityParsingFallsBackToFast() {
        assertEquals(StemQuality.MAX, StemQuality.fromStored("MAX"))
        assertEquals(StemQuality.MAX, StemQuality.fromStored(" max "))
        assertEquals(StemQuality.FAST, StemQuality.fromStored(null))
        assertEquals(StemQuality.FAST, StemQuality.fromStored("ULTRA"))
        assertEquals(setOf("fast", "max"), StemQuality.entries.map { it.folderName }.toSet())
    }

    @Test
    fun stripsFollowTheEngineAndTheCombineToggle() {
        assertEquals(listOf("Vocals", "Drums", "Bass", "Other"), StemSpecs.strips(StemQuality.FAST, false).map { it.label })
        assertEquals(6, StemSpecs.strips(StemQuality.MAX, false).size)
        val combined = StemSpecs.strips(StemQuality.MAX, true)
        assertEquals(listOf("Vocals", "Drums", "Bass", StemSpecs.COMBINED_OTHER_LABEL), combined.map { it.label })
        assertEquals(StemChannel.OTHER, combined.last().channel)
        // The toggle means nothing for Fast.
        assertEquals(StemSpecs.strips(StemQuality.FAST, false), StemSpecs.strips(StemQuality.FAST, true))
    }
}
