package com.beatraxus.app.ui.components.seekbars

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Morphing Blob Seekbar: thumb stretches along drag direction and springs back.
 */
@Composable
fun MorphingBlobSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    seed: Int = 0
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    val stretchFactor = remember { Animatable(1f) } // 1.0 = circle, >1.0 = ellipse
    val scope = rememberCoroutineScope()
    
    val displayProgress = draggingProgress ?: progress

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(seed) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width
                    
                    val initialProgress = (down.position.x / width).coerceIn(0f, 1f)
                    draggingProgress = initialProgress
                    onProgressChange(initialProgress)
                    
                    var lastPos = down.position.x
                    drag(down.id) { change ->
                        val delta = change.position.x - lastPos
                        lastPos = change.position.x
                        
                        // Stretch based on velocity/delta
                        val targetStretch = 1f + (abs(delta) / 10f).coerceAtMost(1.5f)
                        scope.launch {
                            stretchFactor.animateTo(targetStretch, tween(50))
                        }

                        val newProgress = (lastPos / width).coerceIn(0f, 1f)
                        draggingProgress = newProgress
                        onProgressChange(newProgress)
                        change.consume()
                    }
                    
                    draggingProgress = null
                    onProgressFinished((lastPos / width).coerceIn(0f, 1f))
                    
                    // Spring back
                    scope.launch {
                        stretchFactor.animateTo(1f, spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            
            // Track
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 4.dp.toPx()
            )
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(displayProgress * width, centerY),
                strokeWidth = 4.dp.toPx()
            )
            
            // Blob Thumb
            val baseRadius = 10.dp.toPx()
            val stretch = stretchFactor.value
            
            drawOval(
                color = activeColor,
                topLeft = Offset(
                    displayProgress * width - (baseRadius * stretch),
                    centerY - (baseRadius / stretch)
                ),
                size = Size(baseRadius * 2 * stretch, baseRadius * 2 / stretch)
            )
        }
    }
}
