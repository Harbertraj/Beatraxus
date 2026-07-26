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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Constellation Seekbar: small stars connected by faint lines along the
 * track, with a big glowing star as the thumb.
 */
@Composable
fun ConstellationSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFF6C8CFF),
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

    val starOffsets = remember(seed) {
        val r = Random(seed)
        List(22) { r.nextFloat() * 0.7f - 0.35f }
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

            val count = starOffsets.size
            val step = width / (count - 1)
            val points = (0 until count).map { i ->
                val x = i * step
                val y = centerY + starOffsets[i] * height * 0.6f
                Offset(x, y)
            }

            // Connecting lines
            val path = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            drawPath(path, color = inactiveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))

            val playedX = displayProgress * width
            points.forEachIndexed { i, p ->
                val played = p.x <= playedX
                val r = if (i % 4 == 0) 2.5.dp.toPx() else 1.4.dp.toPx()
                drawCircle(
                    color = if (played) activeColor else Color.White.copy(alpha = 0.35f),
                    radius = r,
                    center = p
                )
            }

            // Thumb: big glowing star
            val thumbX = playedX
            // interpolate y along nearest segment
            val segIdx = (displayProgress * (count - 1)).toInt().coerceIn(0, count - 2)
            val t = (displayProgress * (count - 1)) - segIdx
            val y1 = points[segIdx].y
            val y2 = points[segIdx + 1].y
            val thumbY = y1 + (y2 - y1) * t

            for (i in 3 downTo 1) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.12f * i),
                    radius = 6.dp.toPx() + i * 3.dp.toPx(),
                    center = Offset(thumbX, thumbY)
                )
            }
            drawStar(Offset(thumbX, thumbY), outerR = 9.dp.toPx(), innerR = 4.dp.toPx(), color = Color.White)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStar(
    center: Offset,
    outerR: Float,
    innerR: Float,
    color: Color
) {
    val path = Path()
    val points = 5
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outerR else innerR
        val angle = (Math.PI / points * i - Math.PI / 2).toFloat()
        val x = center.x + r * cos(angle)
        val y = center.y + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color)
}
