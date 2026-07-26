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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

/**
 * Galaxy Seekbar: starfield track with small floating planets, and a large
 * glowing planet as the thumb.
 */
@Composable
fun GalaxySeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFF8A4CFF),
    inactiveColor: Color = Color.White.copy(alpha = 0.15f),
    seed: Int = 0,
    dominantColor: Color = Color(0xFF4C8AFF)
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val stars = remember(seed) {
        val r = Random(seed)
        List(45) { Triple(r.nextFloat(), r.nextFloat() * 0.8f - 0.4f, r.nextFloat()) }
    }
    val planets = remember(seed) {
        val r = Random(seed + 1)
        List(3) { r.nextFloat() * 0.8f + 0.1f }
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

            // Stars scattered
            stars.forEach { (sx, sy, sr) ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f + sr * 0.5f),
                    radius = (0.5f + sr) * 1.dp.toPx(),
                    center = Offset(sx * width, centerY + sy * height)
                )
            }

            // Track line
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                brush = Brush.horizontalGradient(listOf(Color(0xFF4C8AFF), activeColor)),
                start = Offset(0f, centerY),
                end = Offset(displayProgress * width, centerY),
                strokeWidth = 2.5.dp.toPx()
            )

            // Small planets along the way
            planets.forEachIndexed { i, px ->
                val x = px * width
                val played = x <= displayProgress * width
                drawCircle(
                    color = if (played) dominantColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.3f),
                    radius = 3.5.dp.toPx(),
                    center = Offset(x, centerY)
                )
            }

            // Thumb: big glowing planet
            val thumbX = displayProgress * width
            for (i in 4 downTo 1) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.08f * i),
                    radius = 8.dp.toPx() + i * 3.dp.toPx(),
                    center = Offset(thumbX, centerY)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White, activeColor, Color(0xFF2A0A55)),
                    center = Offset(thumbX - 2.dp.toPx(), centerY - 2.dp.toPx()),
                    radius = 10.dp.toPx()
                ),
                radius = 8.dp.toPx(),
                center = Offset(thumbX, centerY)
            )
        }
    }
}
