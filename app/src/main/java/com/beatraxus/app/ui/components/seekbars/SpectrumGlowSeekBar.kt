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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * BeatRaxus Waveform Seek Bar
 *
 * Visual style:
 * - Dense audio-waveform bars
 * - Bright green played portion
 * - Soft green glow around the played waveform
 * - Large circular draggable thumb with halo
 * - Muted grey inactive waveform
 *
 * Keeps the same public API as the original SpectrumGlowSeekBar so it can
 * be used as a drop-in replacement.
 */
@Composable
fun SpectrumGlowSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFF9BFF7A),
    inactiveColor: Color = Color.White.copy(alpha = 0.28f),
    seed: Int = 0,
    spectrumData: FloatArray? = null
) {
    var draggingProgress by remember(seed) { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember(seed) { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        val target = lastSeekTarget
        if (target != null && abs(progress - target) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnProgressFinished by rememberUpdatedState(onProgressFinished)

    // Stable pseudo-waveform when real spectrum data is not supplied.
    val heights = remember(seed, spectrumData) {
        spectrumData ?: FloatArray(180) { i ->
            val random = Random(seed * 997 + i * 31)
            val wave1 = kotlin.math.sin(i * 0.21f).let { (it + 1f) * 0.5f }
            val wave2 = kotlin.math.sin(i * 0.065f + 1.4f).let { (it + 1f) * 0.5f }
            val noise = random.nextFloat() * 0.38f
            (0.20f + wave1 * 0.40f + wave2 * 0.22f + noise).coerceIn(0.16f, 1f)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "seekbar_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(seed) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    var lastX = down.position.x

                    val initialProgress =
                        (down.position.x / width).coerceIn(0f, 1f)

                    draggingProgress = initialProgress
                    currentOnProgressChange(initialProgress)

                    drag(down.id) { change ->
                        lastX = change.position.x
                        val newProgress =
                            (lastX / width).coerceIn(0f, 1f)

                        draggingProgress = newProgress
                        currentOnProgressChange(newProgress)
                        change.consume()
                    }

                    val finalProgress =
                        (lastX / width).coerceIn(0f, 1f)

                    draggingProgress = null
                    lastSeekTarget = finalProgress
                    currentOnProgressFinished(finalProgress)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val centerY = height / 2f

            // Dense waveform like the reference image.
            val barWidth = 3.0f
            val gap = 2.15f
            val step = barWidth + gap
            val totalBars = maxOf(1, (width / step).toInt())
            val thumbX = width * displayProgress.coerceIn(0f, 1f)

            // Keep waveform comfortably inside the composable height.
            val maxBarHeight = min(height * 0.78f, 46f)
            val minBarHeight = 4f

            for (i in 0 until totalBars) {
                val x = i * step + barWidth / 2f
                if (x > width) break

                val normalized = i.toFloat() / maxOf(1, totalBars - 1)
                val hFactor = heights[i % heights.size].coerceIn(0.08f, 1f)
                val barHeight = minBarHeight + hFactor * (maxBarHeight - minBarHeight)
                val top = centerY - barHeight / 2f

                val played = normalized <= displayProgress

                if (played) {
                    // Soft halo behind the played waveform.
                    drawRoundRect(
                        color = activeColor.copy(alpha = 0.08f * glowAlpha),
                        topLeft = Offset(x - 3.5f, top - 3f),
                        size = Size(barWidth + 7f, barHeight + 6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )
                }

                drawRoundRect(
                    color = if (played) activeColor else inactiveColor,
                    topLeft = Offset(x - barWidth / 2f, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        barWidth / 2f,
                        barWidth / 2f
                    )
                )
            }

            // Glowing circular seek thumb exactly over the current waveform point.
            val thumbRadius = 11f
            val outerRadius = 18f

            // Large soft glow.
            drawCircle(
                color = activeColor.copy(alpha = 0.10f * glowAlpha),
                radius = outerRadius + 8f,
                center = Offset(thumbX, centerY)
            )

            drawCircle(
                color = activeColor.copy(alpha = 0.18f * glowAlpha),
                radius = outerRadius + 3f,
                center = Offset(thumbX, centerY)
            )

            // Thin green ring.
            drawCircle(
                color = activeColor,
                radius = outerRadius,
                center = Offset(thumbX, centerY),
                style = Stroke(width = 2.5f)
            )

            // White inner thumb.
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbX, centerY)
            )
        }
    }
}
