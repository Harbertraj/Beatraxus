package com.beatraxus.app.ui.components.seekbars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Album Art Gradient Seekbar: uses colors from the album art to create a track gradient.
 */
@Composable
fun AlbumArtGradientSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    seed: Int = 0,
    dominantColor: Color = Color.White
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }
    
    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val displayProgress = draggingProgress ?: lastSeekTarget ?: progress

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
                        lastPos = change.position.x
                        val newProgress = (lastPos / width).coerceIn(0f, 1f)
                        draggingProgress = newProgress
                        onProgressChange(newProgress)
                        change.consume()
                    }
                    
                    val finalProgress = (lastPos / width).coerceIn(0f, 1f)
                    draggingProgress = null
                    lastSeekTarget = finalProgress
                    onProgressFinished(finalProgress)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val trackHeight = 6.dp.toPx()
            val centerY = height / 2f
            
            val gradient = Brush.horizontalGradient(
                colors = listOf(dominantColor.copy(alpha = 0.5f), dominantColor, activeColor)
            )
            
            // Inactive track (dimmed gradient)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(dominantColor.copy(alpha = 0.2f), dominantColor.copy(alpha = 0.3f))
                ),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(width, trackHeight)
            )
            
            // Active track (bright gradient)
            clipRect(right = displayProgress * width) {
                drawRect(
                    brush = gradient,
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(width, trackHeight)
                )
            }
            
            // Thumb
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = Offset(displayProgress * width, centerY)
            )
        }
    }
}
