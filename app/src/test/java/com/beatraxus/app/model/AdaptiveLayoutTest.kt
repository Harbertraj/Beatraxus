package com.beatraxus.app.model

import com.beatraxus.app.util.AdaptiveLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {

    @Test
    fun `phones are not large screens, tablets and unfolded foldables are`() {
        assertFalse(AdaptiveLayout.isLargeScreen(360)) // phone
        assertFalse(AdaptiveLayout.isLargeScreen(411))
        assertFalse(AdaptiveLayout.isLargeScreen(599))
        assertTrue(AdaptiveLayout.isLargeScreen(600))
        assertTrue(AdaptiveLayout.isLargeScreen(673))  // unfolded foldable
        assertTrue(AdaptiveLayout.isLargeScreen(800))  // tablet
    }

    @Test
    fun `grid columns follow the width but keep the minimum`() {
        assertEquals(2, AdaptiveLayout.gridColumns(360, 170))
        assertEquals(2, AdaptiveLayout.gridColumns(411, 170))
        assertEquals(4, AdaptiveLayout.gridColumns(720, 170))
        assertEquals(7, AdaptiveLayout.gridColumns(1280, 170))
        assertEquals(AdaptiveLayout.MAX_COLUMNS, AdaptiveLayout.gridColumns(4000, 170))
        assertEquals(2, AdaptiveLayout.gridColumns(100, 170))
    }

    @Test
    fun `a zero cell width does not crash`() {
        assertEquals(AdaptiveLayout.MAX_COLUMNS, AdaptiveLayout.gridColumns(4000, 0))
    }

    @Test
    fun `a list stays a list and phones keep the users column count`() {
        assertEquals(1, AdaptiveLayout.scaledColumns(1, 1280))
        assertEquals(2, AdaptiveLayout.scaledColumns(2, 360))
        assertEquals(3, AdaptiveLayout.scaledColumns(3, 411))
    }

    @Test
    fun `big screens widen the grid but never narrow it`() {
        assertEquals(4, AdaptiveLayout.scaledColumns(2, 840))
        assertEquals(6, AdaptiveLayout.scaledColumns(6, 400)) // user picked more than the width suggests
        assertEquals(AdaptiveLayout.MAX_COLUMNS, AdaptiveLayout.scaledColumns(2, 4000))
    }

    @Test
    fun `the side sheet is wider on large screens`() {
        assertEquals(320, AdaptiveLayout.sheetMaxWidthDp(360))
        assertEquals(420, AdaptiveLayout.sheetMaxWidthDp(600))
    }
}
