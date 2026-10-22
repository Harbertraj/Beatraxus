package com.beatraxus.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAspectRatioTest {

    @Test
    fun followVideoModesHaveNoForcedRatio() {
        assertNull(VideoAspectRatio.FIT.forcedRatio)
        assertNull(VideoAspectRatio.FILL.forcedRatio)
        assertNull(VideoAspectRatio.ZOOM.forcedRatio)
    }

    @Test
    fun forcedRatiosAreWidthOverHeight() {
        assertEquals(4f / 3f, VideoAspectRatio.FOUR_THREE.forcedRatio!!, 0.0001f)
        assertEquals(16f / 9f, VideoAspectRatio.SIXTEEN_NINE.forcedRatio!!, 0.0001f)
        assertEquals(1.6f, VideoAspectRatio.SIXTEEN_TEN.forcedRatio!!, 0.0001f)
        assertEquals(21f / 9f, VideoAspectRatio.TWENTY_ONE_NINE.forcedRatio!!, 0.0001f)
        assertEquals(2.35f, VideoAspectRatio.CINEMASCOPE.forcedRatio!!, 0.0001f)
    }

    @Test
    fun newRatiosHaveTheLabelsShownOnScreen() {
        assertEquals("16:10", VideoAspectRatio.SIXTEEN_TEN.displayName)
        assertEquals("21:9", VideoAspectRatio.TWENTY_ONE_NINE.displayName)
        assertEquals("2.35:1", VideoAspectRatio.CINEMASCOPE.displayName)
    }

    @Test
    fun buttonCycleVisitsEveryModeOnceAndWrapsToFit() {
        val visited = mutableListOf(VideoAspectRatio.FIT)
        var current = VideoAspectRatio.FIT.next()
        while (current != VideoAspectRatio.FIT) {
            visited += current
            current = current.next()
        }
        assertEquals(VideoAspectRatio.entries.toList(), visited)
        assertEquals(VideoAspectRatio.FIT, VideoAspectRatio.CINEMASCOPE.next())
    }

    @Test
    fun existingModesKeepTheirOrderAndNames() {
        // Saved videos store the entry name, so these must not change.
        assertEquals(
            listOf("FIT", "FILL", "ZOOM", "FOUR_THREE", "SIXTEEN_NINE"),
            VideoAspectRatio.entries.take(5).map { it.name }
        )
        assertTrue(VideoAspectRatio.entries.size == 8)
    }
}
