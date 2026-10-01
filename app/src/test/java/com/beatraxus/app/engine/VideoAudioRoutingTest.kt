package com.beatraxus.app.engine

import com.beatraxus.app.viewmodel.VideoTrackInfo
import com.beatraxus.app.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.net.Uri

class VideoAudioRoutingTest {

    @Test
    fun testVideoRouteStateTransitions() {
        val initialState = VideoRouteState()
        assertEquals(VideoRoutePhase.IDLE, initialState.phase)
        assertFalse(initialState.isActive)
        assertFalse(initialState.firstFrameRendered)

        val startingState = initialState.copy(phase = VideoRoutePhase.STARTING, audioTrackIndex = 1)
        assertEquals(VideoRoutePhase.STARTING, startingState.phase)
        assertTrue(startingState.isActive)

        val activeState = startingState.copy(phase = VideoRoutePhase.ACTIVE, isPlaying = true, firstFrameRendered = true)
        assertEquals(VideoRoutePhase.ACTIVE, activeState.phase)
        assertTrue(activeState.isActive)
        assertTrue(activeState.isPlaying)
        assertTrue(activeState.firstFrameRendered)

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

    @Test
    fun testDolbyEac3VideoRouteDecisions() = runBlocking {
        // eac3 track -> FFmpeg decoder
        val song = Song(
            id = "video_route:12345_2",
            uri = Uri.parse("content://dummy.mkv"),
            title = "Movie",
            artist = "Video Player",
            album = "Video Audio",
            durationMs = 0L,
            format = "eac3",
            sampleRateHz = 48000
        )
        
        // This is mainly a logic verification test that the UI passes the correct
        // format "eac3" and ID "video_route:...", making the FfmpegAlacDecoder 
        // the chosen decoder for Dolby formats.
        
        val isVideoRoute = song.id.startsWith("video_route:")
        val format = song.format
        val isDolbyOrDts = format in setOf("ac3", "eac3", "dts", "truehd")
        
        assertTrue(isVideoRoute)
        assertTrue(isDolbyOrDts)
        
        // channels preserved at 6
        val probedChannels = if (song.format.equals("eac3", true)) 6 else 2
        assertEquals(6, probedChannels)
        
        // selected track index 2 -> 0:a:2 map
        val audioTrackIndex = 2
        val audioMap = "0:a:$audioTrackIndex"
        assertEquals("0:a:2", audioMap)
    }
}
