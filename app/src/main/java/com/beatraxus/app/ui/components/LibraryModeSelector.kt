package com.beatraxus.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.beatraxus.app.model.LibraryMode

/**
 * Library source selector (Local / Cloud / Combined).
 *
 * A clean segmented control: a flat track, one sliding highlight with a short, non-bouncy tween
 * (no jelly squash/stretch or spring overshoot), and an icon-only segment (no text labels) so the
 * three modes are readable at a glance.
 */
@Composable
fun LibraryModeSelector(
    currentMode: LibraryMode,
    onModeSelected: (LibraryMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(LibraryMode.LOCAL, LibraryMode.CLOUD, LibraryMode.COMBINED)
    val selectedIndex = modes.indexOf(currentMode).coerceAtLeast(0)

    val accent = Color(0xFFFFB300)
    val trackShape = RoundedCornerShape(14.dp)
    val segmentShape = RoundedCornerShape(11.dp)

    BoxWithConstraints(
        modifier = modifier
            .height(42.dp)
            .clip(trackShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), trackShape)
            .padding(3.dp)
    ) {
        val itemWidth = maxWidth / modes.size

        // Smooth, critically-damped slide: no overshoot, no scaling.
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "libraryIndicatorOffset"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(segmentShape)
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.55f), segmentShape)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val isSelected = mode == currentMode
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) accent else Color.White.copy(alpha = 0.55f),
                    animationSpec = tween(200),
                    label = "libraryContentColor"
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(segmentShape)
                        .clickable { onModeSelected(mode) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (mode) {
                            LibraryMode.LOCAL -> Icons.Rounded.Storage
                            LibraryMode.COMBINED -> Icons.Rounded.AllInclusive
                            LibraryMode.CLOUD -> Icons.Rounded.Cloud
                            else -> Icons.Rounded.Extension
                        },
                        contentDescription = mode.name,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
