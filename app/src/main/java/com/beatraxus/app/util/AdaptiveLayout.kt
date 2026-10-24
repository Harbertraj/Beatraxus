package com.beatraxus.app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Screen-size rules for tablets and foldables. Pure maths in [AdaptiveLayout] so it can be unit tested.
 *
 * "Large screen" follows the Android convention: the SMALLEST side of the screen is at least 600 dp. That is true
 * for tablets and for an unfolded foldable, and stays false for a phone held sideways.
 */
object AdaptiveLayout {
    const val LARGE_SCREEN_DP = 600
    const val MAX_COLUMNS = 8

    fun isLargeScreen(smallestWidthDp: Int): Boolean = smallestWidthDp >= LARGE_SCREEN_DP

    /** Columns for a grid that should show cells at least [minCellDp] wide. Never below [minColumns]. */
    fun gridColumns(widthDp: Int, minCellDp: Int, minColumns: Int = 2): Int =
        (widthDp / minCellDp.coerceAtLeast(1)).coerceIn(minColumns, MAX_COLUMNS)

    /**
     * The column count the user picked, widened on big screens. A list (1 column) stays a list, and a phone keeps
     * exactly what the user chose.
     */
    fun scaledColumns(userColumns: Int, widthDp: Int, cellDp: Int = 200): Int {
        if (userColumns <= 1) return userColumns
        return maxOf(userColumns, widthDp / cellDp.coerceAtLeast(1)).coerceAtMost(MAX_COLUMNS)
    }

    /** Widest the player's side sheet may be: a little wider on large screens so it does not look tiny. */
    fun sheetMaxWidthDp(smallestWidthDp: Int): Int = if (isLargeScreen(smallestWidthDp)) 420 else 320
}

/** [AdaptiveLayout.gridColumns] for the current window width; follows rotation, folding and split screen. */
@Composable
fun rememberGridColumns(minCellDp: Int, minColumns: Int = 2): Int {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp, minCellDp, minColumns) { AdaptiveLayout.gridColumns(widthDp, minCellDp, minColumns) }
}
