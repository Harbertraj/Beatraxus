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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Rope Seekbar: a twisted rope texture (diagonal hatch strands) with a
 * knot as the thumb.
 */
@Composable
fun RopeSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFFC79A4B),
    inactiveColor: Color = Color(0xFF6B6B70),
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
            val ropeThickness = 8.dp.toPx()
            val thumbX = displayProgress * width

            fun drawStrand(color: Color, from: Float, to: Float) {
                if (to <= from) return
                // Base cylinder
                drawLine(
                    color = color,
                    start = Offset(from, centerY),
                    end = Offset(to, centerY),
                    strokeWidth = ropeThickness,
                    cap = StrokeCap.Round
                )
                // Twist hatching
                val twistSpacing = 7.dp.toPx()
                var x = from
                while (x < to) {
                    val yOff = ropeThickness / 2.4f
                    drawLine(
                        color = Color.Black.copy(alpha = 0.25f),
                        start = Offset(x, centerY - yOff),
                        end = Offset(x + twistSpacing * 0.7f, centerY + yOff),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    x += twistSpacing
                }
                // Highlight
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(from, centerY - ropeThickness / 2.6f),
                    end = Offset(to, centerY - ropeThickness / 2.6f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            drawStrand(inactiveColor, 0f, width)
            drawStrand(activeColor, 0f, thumbX)

            // Knot thumb
            drawCircle(color = Color.Black.copy(alpha = 0.3f), radius = ropeThickness * 1.5f, center = Offset(thumbX, centerY))
            drawCircle(
                color = activeColor,
                radius = ropeThickness * 1.3f,
                center = Offset(thumbX, centerY)
            )
            // loop lines suggesting a knot
            for (i in 0 until 3) {
                val angle = (i * 60).toDouble()
                val dx = (ropeThickness * 1.1f) * sin(Math.toRadians(angle)).toFloat()
                val dy = (ropeThickness * 0.6f) * sin(Math.toRadians(angle + 90)).toFloat()
                drawLine(
                    color = Color.Black.copy(alpha = 0.35f),
                    start = Offset(thumbX - dx, centerY - dy),
                    end = Offset(thumbX + dx, centerY + dy),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            drawCircle(color = Color.White.copy(alpha = 0.15f), radius = ropeThickness * 0.5f, center = Offset(thumbX - 2.dp.toPx(), centerY - 2.dp.toPx()))
        }
    }
}
