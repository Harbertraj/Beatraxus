package com.beatraxus.app.ui.components.seekbars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.beatraxus.app.model.ChapterEntity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.abs

/**
 * Smart Chapter Seekbar: Divided into colored segments representing track chapters.
 */
@Composable
fun SmartChapterSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    durationMs: Long = 0L,
    chapters: List<ChapterEntity> = emptyList()
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }
    
    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnProgressFinished by rememberUpdatedState(onProgressFinished)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width
                    val initialProgress = (down.position.x / width).coerceIn(0f, 1f)
                    draggingProgress = initialProgress
                    currentOnProgressChange(initialProgress)
                    
                    var lastPos = down.position.x
                    drag(down.id) { change ->
                        lastPos = change.position.x
                        val newProgress = (lastPos / width).coerceIn(0f, 1f)
                        draggingProgress = newProgress
                        currentOnProgressChange(newProgress)
                        change.consume()
                    }
                    
                    val finalProgress = (lastPos / width).coerceIn(0f, 1f)
                    draggingProgress = null
                    lastSeekTarget = finalProgress
                    currentOnProgressFinished(finalProgress)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val trackHeight = 8.dp.toPx()
            val centerY = height / 2f
            
            // Draw background
            drawRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(width, trackHeight)
            )

            // Draw chapters
            if (chapters.isNotEmpty() && durationMs > 0) {
                chapters.forEachIndexed { index, chapter ->
                    val startX = (chapter.startMs.toFloat() / durationMs.toFloat()) * width
                    val nextChapterStart = if (index < chapters.size - 1) chapters[index + 1].startMs else durationMs
                    val endX = (nextChapterStart.toFloat() / durationMs.toFloat()) * width
                    val chapterWidth = endX - startX
                    
                    val isPartiallyPlayed = (startX / width) <= displayProgress
                    val alpha = if (isPartiallyPlayed) 1f else 0.4f
                    
                    drawRect(
                        color = Color(chapter.color).copy(alpha = alpha),
                        topLeft = Offset(startX, centerY - trackHeight / 2f),
                        size = Size(chapterWidth, trackHeight)
                    )
                    
                    // Separator
                    if (index > 0) {
                        drawLine(
                            color = Color.Black.copy(alpha = 0.5f),
                            start = Offset(startX, centerY - trackHeight / 2f),
                            end = Offset(startX, centerY + trackHeight / 2f),
                            strokeWidth = 2f
                        )
                    }
                }
            }

            // Draw progress overlay (active color highlight)
            drawRect(
                color = activeColor.copy(alpha = 0.3f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(displayProgress * width, trackHeight)
            )
            
            // Thumb
            drawCircle(
                color = activeColor,
                radius = 6.dp.toPx(),
                center = Offset(displayProgress * width, centerY)
            )
        }
    }
}
