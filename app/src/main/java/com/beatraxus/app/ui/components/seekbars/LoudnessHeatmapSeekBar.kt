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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

/**
 * Loudness Heatmap Seekbar: track thickness and brightness represent audio loudness (RMS).
 */
@Composable
fun LoudnessHeatmapSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    seed: Int = 0,
    loudnessData: FloatArray? = null
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }
    
    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
    
    // Fallback to random data if not provided
    val data = remember(seed, loudnessData) {
        loudnessData ?: FloatArray(100) { i ->
            Random(seed + i).nextFloat() * 0.8f + 0.2f
        }
    }

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
            val centerY = height / 2f
            
            val barCount = data.size
            val barWidth = width / barCount
            
            for (i in 0 until barCount) {
                val x = i * barWidth
                val loudness = data[i]
                val isPlayed = (x / width) <= displayProgress
                
                val barHeight = height * loudness
                val color = if (isPlayed) activeColor else inactiveColor
                
                drawRect(
                    color = color.copy(alpha = if (isPlayed) 1f else 0.4f),
                    topLeft = Offset(x, centerY - barHeight / 2f),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}
