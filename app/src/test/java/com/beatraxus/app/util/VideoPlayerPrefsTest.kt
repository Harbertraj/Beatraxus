package com.beatraxus.app.util

import com.beatraxus.app.subtitles.data.SubtitleStylePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerPrefsTest {

    @Test
    fun defaultsKeepTodaysBehaviour() {
        val o = VideoPlayerOptions()
        assertTrue(o.gestureBrightness && o.gestureVolume && o.gestureSeek && o.gestureZoom && o.gestureSubtitleMove)
        assertEquals(10, o.doubleTapSeekSeconds)
        assertEquals(10_000L, o.doubleTapSeekMs)
        assertEquals(1f, o.defaultSpeed, 0f)
        assertEquals(VideoOrientationMode.LANDSCAPE, o.orientationMode)
        assertTrue(o.resumePlayback && o.autoPlayNext && o.pauseOnHeadsetUnplug && o.keepScreenOn)
    }

    @Test
    fun unsupportedSavedValuesFallBackToDefaults() {
        assertEquals(10, VideoPlayerPrefs.coerceDoubleTapSeconds(7))
        assertEquals(30, VideoPlayerPrefs.coerceDoubleTapSeconds(30))
        assertEquals(2f, VideoPlayerPrefs.coerceHoldSpeed(9f), 0f)
        assertEquals(3f, VideoPlayerPrefs.coerceHoldSpeed(3f), 0f)
        assertEquals(1f, VideoPlayerPrefs.coerceDefaultSpeed(0.1f), 0f)
        assertEquals(0.75f, VideoPlayerPrefs.coerceDefaultSpeed(0.75f), 0f)
    }

    @Test
    fun speedLabelsDropTheTrailingZero() {
        assertEquals("2", VideoPlayerPrefs.formatSpeed(2f))
        assertEquals("1.5", VideoPlayerPrefs.formatSpeed(1.5f))
        assertEquals("0.75", VideoPlayerPrefs.formatSpeed(0.75f))
    }

    @Test
    fun orientationModeRoundTripsAndFallsBackToLandscape() {
        for (mode in VideoOrientationMode.entries) {
            assertEquals(mode, VideoOrientationMode.fromName(mode.name))
        }
        assertEquals(VideoOrientationMode.LANDSCAPE, VideoOrientationMode.fromName(null))
        assertEquals(VideoOrientationMode.LANDSCAPE, VideoOrientationMode.fromName("SIDEWAYS"))
    }

    @Test
    fun languageChoicesStartWithAutoAndHaveUniqueCodes() {
        val choices = VideoPlayerPrefs.LANGUAGE_CHOICES
        assertEquals(null, choices.first().first)
        assertEquals(choices.size, choices.map { it.first }.toSet().size)
    }

    @Test
    fun subtitleSizeFollowsThePlayerRule() {
        assertEquals(18f, SubtitleStylePrefs.sizeSpFor(100), 0.001f)
        assertEquals(36f, SubtitleStylePrefs.sizeSpFor(200), 0.001f)
        // 50% would be 9sp, but the player never goes below 12sp.
        assertEquals(12f, SubtitleStylePrefs.sizeSpFor(50), 0.001f)
    }
}
