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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Heartbeat Seekbar: ECG pulse line, red for the played portion, with a
 * glowing heart-shaped thumb.
 */
@Composable
fun HeartbeatSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFFFF3B4E),
    inactiveColor: Color = Color.White.copy(alpha = 0.2f),
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

            // Build one repeating ECG "lub-dub" spike pattern across the width
            fun ecgPath(color: Color, from: Float, to: Float) {
                val path = Path()
                val periodPx = 42.dp.toPx()
                var x = from
                var first = true
                path.moveTo(x, centerY)
                while (x < to) {
                    val localX = x % periodPx
                    val y = when {
                        localX < periodPx * 0.35f -> centerY
                        localX < periodPx * 0.42f -> centerY - height * 0.12f
                        localX < periodPx * 0.48f -> centerY + height * 0.42f
                        localX < periodPx * 0.55f -> centerY - height * 0.55f
                        localX < periodPx * 0.62f -> centerY + height * 0.15f
                        localX < periodPx * 0.7f -> centerY
                        localX < periodPx * 0.82f -> centerY - height * 0.08f
                        else -> centerY
                    }
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    x += 2f
                }
                drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            val thumbX = displayProgress * width
            ecgPath(inactiveColor, 0f, width)
            ecgPath(activeColor, 0f, thumbX)

            // Glow + heart thumb
            for (i in 3 downTo 1) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.12f * i),
                    radius = 7.dp.toPx() + i * 3.dp.toPx(),
                    center = Offset(thumbX, centerY)
                )
            }
            drawHeart(Offset(thumbX, centerY), size = 9.dp.toPx(), color = activeColor)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeart(
    center: Offset,
    size: Float,
    color: Color
) {
    val path = Path()
    val x = center.x
    val y = center.y
    path.moveTo(x, y + size * 0.6f)
    path.cubicTo(x - size * 1.3f, y - size * 0.4f, x - size * 0.4f, y - size * 1.3f, x, y - size * 0.35f)
    path.cubicTo(x + size * 0.4f, y - size * 1.3f, x + size * 1.3f, y - size * 0.4f, x, y + size * 0.6f)
    path.close()
    drawPath(path, color = color)
    drawPath(path, color = Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.dp.toPx()))
}
