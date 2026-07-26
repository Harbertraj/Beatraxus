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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Vinyl Groove Seekbar: a record groove track with a gold tonearm/stylus thumb.
 */
@Composable
fun VinylGrooveSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFFE0A72E),
    inactiveColor: Color = Color.White.copy(alpha = 0.15f),
    seed: Int = 0
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
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
            val displayProgress = draggingProgress ?: lastSeekTarget ?: progress

            // Concentric vinyl groove rings in the background
            val ringSpacing = 5.dp.toPx()
            var ringY = 3.dp.toPx()
            while (ringY < height / 2f) {
                drawLine(
                    color = Color.White.copy(alpha = 0.04f),
                    start = Offset(0f, centerY - ringY),
                    end = Offset(width, centerY - ringY),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.04f),
                    start = Offset(0f, centerY + ringY),
                    end = Offset(width, centerY + ringY),
                    strokeWidth = 1.dp.toPx()
                )
                ringY += ringSpacing
            }

            // Track groove line
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Played groove
            drawLine(
                brush = Brush.horizontalGradient(listOf(activeColor.copy(alpha = 0.6f), activeColor)),
                start = Offset(0f, centerY),
                end = Offset(displayProgress * width, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Stylus/tonearm thumb
            val thumbX = displayProgress * width
            drawRoundRect(
                color = Color(0xFF2A2A2E),
                topLeft = Offset(thumbX - 2.dp.toPx(), centerY - 9.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(20.dp.toPx(), 18.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            drawLine(
                color = activeColor,
                start = Offset(thumbX + 3.dp.toPx(), centerY - 5.dp.toPx()),
                end = Offset(thumbX + 3.dp.toPx(), centerY + 5.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = activeColor.copy(alpha = 0.9f),
                radius = 2.dp.toPx(),
                center = Offset(thumbX - 1.dp.toPx(), centerY)
            )
        }
    }
}
