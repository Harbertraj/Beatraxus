package com.beatflowy.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.beatflowy.app.model.LibraryMode
import kotlinx.coroutines.launch

@Composable
fun LibraryModeSelector(
    currentMode: LibraryMode,
    onModeSelected: (LibraryMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(LibraryMode.LOCAL, LibraryMode.CLOUD, LibraryMode.COMBINED)
    val selectedIndex = modes.indexOf(currentMode)
    
    // Unique color for this component: Sunset Amber / Gold
    val componentColor = Color(0xFFFFB300) 

    val jellyScaleX = remember { Animatable(1f) }
    val jellyScaleY = remember { Animatable(1f) }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            launch {
                // Initial squash/stretch
                jellyScaleX.animateTo(1.25f, spring(stiffness = Spring.StiffnessMedium))
                // Bounce back to normal
                jellyScaleX.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow))
            }
            launch {
                jellyScaleY.animateTo(0.75f, spring(stiffness = Spring.StiffnessMedium))
                jellyScaleY.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow))
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(22.dp))
            .padding(4.dp)
    ) {
        val maxWidth = maxWidth
        val itemWidth = maxWidth / 3
        
        // Jelly effect: Bouncy spring for offset
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = 0.6f, 
                stiffness = 300f 
            ),
            label = "indicatorOffset"
        )

        // Sliding background indicator
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = jellyScaleX.value
                    scaleY = jellyScaleY.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                }
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(componentColor.copy(0.2f), componentColor.copy(0.4f))
                    )
                )
                .border(1.dp, componentColor.copy(0.5f), RoundedCornerShape(18.dp))
        )



        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val isSelected = mode == currentMode
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) componentColor else Color.White.copy(alpha = 0.4f),
                    label = "iconColor"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.2f else 1f,
                    label = "iconScale"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onModeSelected(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (mode) {
                            LibraryMode.LOCAL -> Icons.Rounded.Storage
                            LibraryMode.COMBINED -> Icons.Rounded.AllInclusive
                            LibraryMode.CLOUD -> Icons.Rounded.Cloud
                        },
                        contentDescription = mode.name,
                        tint = iconColor,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    )
                }
            }
        }
    }
}
