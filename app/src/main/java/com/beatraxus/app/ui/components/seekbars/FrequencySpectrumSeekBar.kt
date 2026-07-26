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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

/**
 * Frequency Spectrum Seekbar: dense green EQ-style bars with a tall glowing
 * "now playing" bar marking the playhead.
 */
@Composable
fun FrequencySpectrumSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFF7ED321),
    inactiveColor: Color = Color(0xFF7ED321).copy(alpha = 0.25f),
    seed: Int = 0,
    spectrumData: FloatArray? = null
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val barHeights = remember(seed, spectrumData) {
        if (spectrumData != null && spectrumData.isNotEmpty()) {
            spectrumData.toList()
        } else {
            val r = Random(seed)
            List(80) { r.nextFloat() * 0.8f + 0.15f }
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

            val barWidth = 2.5.dp.toPx()
            val gap = 1.5.dp.toPx()
            val step = barWidth + gap
            val totalBars = (width / step).toInt()
            val playedIndex = (displayProgress * totalBars).toInt()

            for (i in 0 until totalBars) {
                val x = i * step
                val hFactor = barHeights[i % barHeights.size]
                val barHeight = height * hFactor * 0.9f
                val isPlayed = i <= playedIndex
                val color = if (isPlayed) activeColor else inactiveColor
                drawLine(
                    color = color,
                    start = Offset(x, centerY - barHeight / 2f),
                    end = Offset(x, centerY + barHeight / 2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }

            // Playhead marker: tall glowing bar
            val thumbX = displayProgress * width
            for (i in 3 downTo 1) {
                drawRect(
                    color = Color.White.copy(alpha = 0.08f * i),
                    topLeft = Offset(thumbX - (barWidth + i * 2.dp.toPx()) / 2f, 0f),
                    size = Size(barWidth + i * 2.dp.toPx(), height)
                )
            }
            drawLine(
                color = Color.White,
                start = Offset(thumbX, 0f),
                end = Offset(thumbX, height),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
