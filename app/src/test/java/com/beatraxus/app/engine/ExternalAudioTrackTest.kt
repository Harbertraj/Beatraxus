package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioTrackTest {

    @Test
    fun labelDropsTheFileExtension() {
        assertEquals("My Dub", ExternalAudioTrack.labelFromFileName("My Dub.mka"))
        assertEquals("Movie.2024.English", ExternalAudioTrack.labelFromFileName("Movie.2024.English.eac3"))
    }

    @Test
    fun labelKeepsNamesWithoutARealExtension() {
        assertEquals("commentary", ExternalAudioTrack.labelFromFileName("commentary"))
        // A leading dot is a hidden file, not an extension.
        assertEquals(".hidden", ExternalAudioTrack.labelFromFileName(".hidden"))
    }

    @Test
    fun blankNamesFallBackToDefault() {
        assertEquals(ExternalAudioTrack.DEFAULT_LABEL, ExternalAudioTrack.labelFromFileName(null))
        assertEquals(ExternalAudioTrack.DEFAULT_LABEL, ExternalAudioTrack.labelFromFileName("   "))
    }

    @Test
    fun mergedGroupIdsAreToldApart() {
        assertTrue(ExternalAudioTrack.isExternalGroupId("1:0"))
        assertTrue(ExternalAudioTrack.isExternalGroupId("1:audio"))
        assertFalse(ExternalAudioTrack.isExternalGroupId("0:1"))
        assertFalse(ExternalAudioTrack.isExternalGroupId("1"))
        assertFalse(ExternalAudioTrack.isExternalGroupId(null))
    }
}
