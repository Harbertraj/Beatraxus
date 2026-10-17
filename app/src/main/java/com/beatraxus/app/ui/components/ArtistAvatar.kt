package com.beatraxus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Deterministic initials avatar for artist tiles.
 *
 * Used as a fallback when no embedded album art is available.
 * The background hue is derived from the artist name's hash, so the same artist
 * always gets the same color across sessions — no storage needed.
 *
 * Legal note: entirely on-device, no third-party images, no network calls,
 * no publicity/personality rights concerns.
 */
private val INITIALS_SPLIT = Regex("\\s+")

@Composable
fun ArtistAvatar(
    name: String,
    modifier: Modifier = Modifier
) {
    // Computed once per name (a Regex was compiled on every recomposition of every tile while scrolling).
    val (initials, bgColor) = remember(name) {
        // Up to two initials: first letter of each whitespace-separated word
        val ini = name.trim()
            .split(INITIALS_SPLIT)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")
            .ifEmpty { "?" }
        // Deterministic hue — same artist name always maps to the same color
        val h = (name.hashCode().and(0xFFFFFF) % 360).let {
            if (it < 0) it + 360 else it  // hashCode() can be negative
        }.toFloat()
        ini to Color.hsv(h, 0.45f, 0.45f)
    }

    Box(
        modifier = modifier.background(bgColor, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
