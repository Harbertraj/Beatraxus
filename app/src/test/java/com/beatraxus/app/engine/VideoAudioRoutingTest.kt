package com.beatraxus.app.engine

import com.beatraxus.app.viewmodel.VideoTrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAudioRoutingTest {

    @Test
    fun testVideoRouteStateTransitions() {
        val initialState = VideoRouteState()
        assertEquals(VideoRoutePhase.IDLE, initialState.phase)
        assertFalse(initialState.isActive)

        val startingState = initialState.copy(phase = VideoRoutePhase.STARTING, audioTrackIndex = 1)
        assertEquals(VideoRoutePhase.STARTING, startingState.phase)
        assertTrue(startingState.isActive)

        val activeState = startingState.copy(phase = VideoRoutePhase.ACTIVE, isPlaying = true)
        assertEquals(VideoRoutePhase.ACTIVE, activeState.phase)
        assertTrue(activeState.isActive)
        assertTrue(activeState.isPlaying)

        val errorState = activeState.copy(phase = VideoRoutePhase.ERROR, isPlaying = false, error = "Decoder failed")
        assertEquals(VideoRoutePhase.ERROR, errorState.phase)
        assertFalse(errorState.isActive)
        assertEquals("Decoder failed", errorState.error)

        val idleState = activeState.copy(phase = VideoRoutePhase.IDLE, isPlaying = false)
        assertEquals(VideoRoutePhase.IDLE, idleState.phase)
        assertFalse(idleState.isActive)
    }

    @Test
    fun testVideoTrackInfoAudioOrdinalMapping() {
        // Group indices: 0 (video), 1 (audio 1), 2 (audio 2), 3 (text)
        val track1 = VideoTrackInfo(index = 1, name = "English", language = "en", format = "audio/aac", isSelected = true, audioOrdinal = 0)
        val track2 = VideoTrackInfo(index = 2, name = "Spanish", language = "es", format = "audio/ac3", isSelected = false, audioOrdinal = 1)

        assertEquals(1, track1.index)
        assertEquals(0, track1.audioOrdinal)

        assertEquals(2, track2.index)
        assertEquals(1, track2.audioOrdinal)
    }
}
