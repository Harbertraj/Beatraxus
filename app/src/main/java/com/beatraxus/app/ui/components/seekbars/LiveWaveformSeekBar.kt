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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live Waveform Seekbar: mirrored audio waveform bars that "pulse" near the
 * playhead, played portion in bright magenta, remainder dimmed grey.
 */
@Composable
fun LiveWaveformSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFFE23AF0),
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

    val infinite = rememberInfiniteTransition(label = "wave_pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "pulse"
    )

    val barHeights = remember(seed) {
        val r = Random(seed)
        List(90) { r.nextFloat() * 0.75f + 0.15f }
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
            val barWidth = 3.dp.toPx()
            val gap = 2.5.dp.toPx()
            val step = barWidth + gap
            val totalBars = (width / step).toInt()
            val playedIndex = (displayProgress * totalBars).toInt()

            for (i in 0 until totalBars) {
                val x = i * step
                val hFactor = barHeights[i % barHeights.size]
                var barHeight = height * hFactor
                val isPlayed = i <= playedIndex
                val distFromPlayhead = abs(i - playedIndex)

                if (distFromPlayhead < 3) {
                    val wobble = 1f + 0.3f * sin(pulse * 2 * Math.PI.toFloat() + i)
                    barHeight *= wobble.coerceIn(0.7f, 1.4f)
                }

                val color = if (isPlayed) activeColor else inactiveColor
                drawLine(
                    color = color,
                    start = Offset(x, centerY - barHeight / 2f),
                    end = Offset(x, centerY + barHeight / 2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }

            // Playhead glow marker
            val thumbX = displayProgress * width
            for (i in 3 downTo 1) {
                drawLine(
                    color = activeColor.copy(alpha = 0.15f * i),
                    start = Offset(thumbX, centerY - height / 2f),
                    end = Offset(thumbX, centerY + height / 2f),
                    strokeWidth = i * 3.dp.toPx()
                )
            }
            drawCircle(
                color = activeColor,
                radius = 3.5.dp.toPx(),
                center = Offset(thumbX, centerY - height / 2f + 2.dp.toPx())
            )
        }
    }
}
