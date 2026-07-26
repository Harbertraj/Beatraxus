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
import kotlin.math.sin

/**
 * Liquid Flow Seekbar: an undulating wave fill from blue to purple to pink,
 * with a floating glassy bubble thumb.
 */
@Composable
fun LiquidFlowSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFFB03AF0),
    inactiveColor: Color = Color.White.copy(alpha = 0.12f),
    seed: Int = 0
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val infinite = rememberInfiniteTransition(label = "liquid_flow")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase"
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
            val centerY = height / 2f
            val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
            val amplitude = height * 0.18f
            val waveWidth = 40.dp.toPx()

            // Base track
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 3.dp.toPx()
            )

            // Wavy filled path up to progress
            val fillX = displayProgress * width
            if (fillX > 1f) {
                val path = Path().apply {
                    moveTo(0f, centerY)
                    var x = 0f
                    while (x <= fillX) {
                        val y = centerY + amplitude * sin((x / waveWidth) + phase)
                        lineTo(x, y)
                        x += 4f
                    }
                    lineTo(fillX, centerY)
                    close()
                }
                drawPath(
                    path,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF2EBEE0), Color(0xFF7B3AF0), Color(0xFFEA5FD0)),
                        endX = fillX.coerceAtLeast(1f)
                    )
                )
            }

            // Bubble thumb
            val thumbX = fillX
            val thumbY = centerY + amplitude * sin((thumbX / waveWidth) + phase)
            drawCircle(color = Color.White.copy(alpha = 0.15f), radius = 14.dp.toPx(), center = Offset(thumbX, thumbY))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.9f), activeColor.copy(alpha = 0.6f)),
                    center = Offset(thumbX - 3.dp.toPx(), thumbY - 3.dp.toPx()),
                    radius = 12.dp.toPx()
                ),
                radius = 9.dp.toPx(),
                center = Offset(thumbX, thumbY)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = 2.dp.toPx(),
                center = Offset(thumbX - 3.dp.toPx(), thumbY - 3.dp.toPx())
            )
        }
    }
}
