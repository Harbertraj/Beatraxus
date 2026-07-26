package com.beatraxus.app.ui.components.seekbars

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Magnetic Floating Seekbar: a thin line with a map-pin style thumb that
 * hovers with pulsing magnetic rings underneath it.
 */
@Composable
fun MagneticFloatingSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFFFF8A2E),
    inactiveColor: Color = Color.White.copy(alpha = 0.25f),
    seed: Int = 0
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val infinite = rememberInfiniteTransition(label = "magnetic_pulse")
    val ringPulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "ring"
    )

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
            val centerY = height / 2f + height * 0.18f
            val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
            val thumbX = displayProgress * width
            val pinHeight = height * 0.75f
            val pinTopY = centerY - pinHeight

            // Thin track
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = 2.dp.toPx()
            )

            // Magnetic pulse rings on the ground
            for (i in 0 until 3) {
                val t = ((ringPulse + i / 3f) % 1f)
                drawCircle(
                    color = activeColor.copy(alpha = (1f - t) * 0.35f),
                    radius = 4.dp.toPx() + t * 16.dp.toPx(),
                    center = Offset(thumbX, centerY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                )
            }

            // Pin body (teardrop / map marker)
            val r = pinHeight * 0.32f
            val pinCenter = Offset(thumbX, pinTopY + r)
            val pin = Path().apply {
                moveTo(thumbX, centerY - 2.dp.toPx())
                cubicTo(
                    thumbX - r * 1.4f, pinCenter.y + r * 0.6f,
                    thumbX - r * 1.4f, pinCenter.y - r * 1.4f,
                    thumbX, pinCenter.y - r * 1.4f
                )
                cubicTo(
                    thumbX + r * 1.4f, pinCenter.y - r * 1.4f,
                    thumbX + r * 1.4f, pinCenter.y + r * 0.6f,
                    thumbX, centerY - 2.dp.toPx()
                )
                close()
            }
            drawPath(pin, brush = Brush.verticalGradient(listOf(Color(0xFFFFC46B), activeColor), startY = pinTopY, endY = centerY))
            drawCircle(color = Color.White, radius = r * 0.42f, center = pinCenter)
            drawCircle(color = activeColor, radius = r * 0.42f, center = pinCenter, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }
    }
}
