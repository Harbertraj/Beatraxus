package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSnapshotSpecTest {

    @Test
    fun sizeScalesUpToTheVideoHeightKeepingProportions() {
        val (w, h) = VideoSnapshotSpec.targetSize(1280, 720, 1080)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun sizeNeverShrinksBelowTheView() {
        assertEquals(1280 to 720, VideoSnapshotSpec.targetSize(1280, 720, 480))
        assertEquals(1280 to 720, VideoSnapshotSpec.targetSize(1280, 720, 0))
    }

    @Test
    fun hugeVideosAreCappedToMaxSide() {
        val (w, h) = VideoSnapshotSpec.targetSize(1920, 1080, 4320) // 8K tall
        assertTrue(maxOf(w, h) <= VideoSnapshotSpec.MAX_SIDE)
        assertEquals(16.0 / 9.0, w.toDouble() / h, 0.01)
    }

    @Test
    fun emptyViewGivesAOnePixelBitmapNotACrash() {
        assertEquals(1 to 1, VideoSnapshotSpec.targetSize(0, 0, 1080))
    }

    @Test
    fun positionIsHoursMinutesSeconds() {
        assertEquals("00-00-00", VideoSnapshotSpec.formatPosition(0))
        assertEquals("01-02-05", VideoSnapshotSpec.formatPosition(3_725_000))
        assertEquals("00-00-00", VideoSnapshotSpec.formatPosition(-5))
    }

    @Test
    fun titleIsMadeSafeForFileNames() {
        assertEquals("My_Movie_2024", VideoSnapshotSpec.safeTitle("My Movie (2024).mkv"))
        assertEquals("a_b", VideoSnapshotSpec.safeTitle("a/b:?*"))
        assertEquals("video", VideoSnapshotSpec.safeTitle(""))
        assertEquals("video", VideoSnapshotSpec.safeTitle(null))
        assertEquals("video", VideoSnapshotSpec.safeTitle("???"))
        assertTrue(VideoSnapshotSpec.safeTitle("x".repeat(200)).length <= 60)
    }

    @Test
    fun fileNameHasTitlePositionStampAndExtension() {
        val name = VideoSnapshotSpec.fileName("Holiday.mp4", 61_000, 0L)
        assertTrue(name, Regex("Holiday_00-01-01_\\d{8}-\\d{6}\\.jpg").matches(name))
    }
}
