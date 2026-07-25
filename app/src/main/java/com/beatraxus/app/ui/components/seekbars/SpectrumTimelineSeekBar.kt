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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.random.Random

/**
 * Spectrum Timeline Seekbar: bar-height timeline that mimics a frequency spectrum.
 * Animated played portion with a subtle glow.
 */
@Composable
fun SpectrumTimelineSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    seed: Int = 0,
    spectrumData: FloatArray? = null
) {
    var draggingProgress by remember(seed) { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember(seed) { mutableStateOf<Float?>(null) }
    
    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnProgressFinished by rememberUpdatedState(onProgressFinished)

    // Generate stable pseudo-spectrum if no real data is provided
    val heights = remember(seed, spectrumData) {
        spectrumData ?: FloatArray(120) { i ->
            val r = Random(seed + i)
            // Heuristic: lower frequencies (left) are taller, higher (right) are thinner
            val factor = 1f - (i.toFloat() / 120f) * 0.5f
            (r.nextFloat() * 0.7f + 0.3f) * factor
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "spectrum_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
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
                    val width = size.width
                    val initialProgress = (down.position.x / width).coerceIn(0f, 1f)
                    draggingProgress = initialProgress
                    currentOnProgressChange(initialProgress)
                    
                    var lastPos = down.position.x
                    drag(down.id) { change ->
                        lastPos = change.position.x
                        val newProgress = (lastPos / width).coerceIn(0f, 1f)
                        draggingProgress = newProgress
                        currentOnProgressChange(newProgress)
                        change.consume()
                    }
                    
                    val finalProgress = (lastPos / width).coerceIn(0f, 1f)
                    draggingProgress = null
                    lastSeekTarget = finalProgress
                    currentOnProgressFinished(finalProgress)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val barWidth = 4f
            val gap = 2f
            val totalBars = (width / (barWidth + gap)).toInt()
            
            for (i in 0 until totalBars) {
                val x = i * (barWidth + gap)
                val barProgress = x / width
                val isPlayed = barProgress <= displayProgress
                
                val hFactor = heights[i % heights.size]
                val barHeight = height * hFactor
                
                val color = if (isPlayed) activeColor else inactiveColor
                
                // Draw glow for active portion
                if (isPlayed) {
                    drawRect(
                        color = activeColor.copy(alpha = 0.1f * glowAlpha),
                        topLeft = Offset(x - 2f, (height - barHeight) / 2f - 2f),
                        size = Size(barWidth + 4f, barHeight + 4f)
                    )
                }

                drawRect(
                    color = color,
                    topLeft = Offset(x, (height - barHeight) / 2f),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}
