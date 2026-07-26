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
 * Sound Particles Seekbar: a dense trail of tiny glowing dots up to the
 * playhead that fades into sparse dust after it, ending in a bright orb thumb.
 */
@Composable
fun SoundParticlesSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFF2EE6C8),
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

    val dust = remember(seed) {
        val r = Random(seed)
        List(140) { Triple(r.nextFloat(), r.nextFloat() * 0.6f - 0.3f, r.nextFloat()) }
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
            val thumbX = displayProgress * width

            // Thin base line for unplayed section
            drawLine(
                color = inactiveColor.copy(alpha = 0.15f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.dp.toPx()
            )

            dust.forEach { (dx, dy, dr) ->
                val x = dx * width
                val y = centerY + dy * height
                val played = x <= thumbX
                val distFromPlayhead = abs(x - thumbX)
                val alpha = if (played) {
                    (0.3f + dr * 0.6f)
                } else {
                    // sparse fading dust after the playhead
                    (0.5f - (distFromPlayhead / width)).coerceIn(0f, 0.4f) * dr
                }
                if (alpha > 0.02f) {
                    drawCircle(
                        color = (if (played) activeColor else Color.White).copy(alpha = alpha),
                        radius = (0.6f + dr * 1.4f) * 1.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            // Bright orb thumb
            for (i in 3 downTo 1) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.15f * i),
                    radius = 6.dp.toPx() + i * 3.dp.toPx(),
                    center = Offset(thumbX, centerY)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, activeColor)),
                radius = 6.dp.toPx(),
                center = Offset(thumbX, centerY)
            )
        }
    }
}
