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
import com.beatraxus.app.model.LrcLine
import kotlin.math.abs

/**
 * Lyrics Marker Seekbar: places markers at each lyric line timestamp.
 */
@Composable
fun LyricsMarkerSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    durationMs: Long = 0L,
    lyrics: List<LrcLine> = emptyList()
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
            .pointerInput(Unit) {
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
            
            // Track
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 2.dp.toPx()
            )
            
            // Markers
            if (durationMs > 0) {
                lyrics.forEach { line ->
                    val markerX = (line.startTime.toFloat() / durationMs.toFloat()) * width
                    val isPlayed = (line.startTime.toFloat() / durationMs.toFloat()) <= displayProgress
                    
                    drawRect(
                        color = if (isPlayed) activeColor else activeColor.copy(alpha = 0.4f),
                        topLeft = Offset(markerX - 1.dp.toPx(), centerY - 6.dp.toPx()),
                        size = Size(2.dp.toPx(), 12.dp.toPx())
                    )
                }
            }
            
            // Thumb
            drawCircle(
                color = activeColor,
                radius = 6.dp.toPx(),
                center = Offset(displayProgress * width, centerY)
            )
        }
    }
}
