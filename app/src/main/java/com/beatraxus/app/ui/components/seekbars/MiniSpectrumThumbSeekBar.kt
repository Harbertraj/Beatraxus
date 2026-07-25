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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

/**
 * Mini Spectrum Thumb Seekbar: a standard seekbar with a tiny animated spectrum inside the thumb.
 */
@Composable
fun MiniSpectrumThumbSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    seed: Int = 0,
    dominantColor: Color = Color.White
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }
    
    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val displayProgress = draggingProgress ?: lastSeekTarget ?: progress

    val infiniteTransition = rememberInfiniteTransition(label = "mini_spectrum")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
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
            
            // Track
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 4.dp.toPx()
            )
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(displayProgress * width, centerY),
                strokeWidth = 4.dp.toPx()
            )
            
            // Large Thumb
            val thumbRadius = 16.dp.toPx()
            val thumbCenter = Offset(displayProgress * width, centerY)
            
            drawCircle(
                color = dominantColor,
                radius = thumbRadius,
                center = thumbCenter
            )
            
            // Mini Spectrum inside thumb
            val barWidth = 3f
            val gap = 2f
            val bars = 5
            val spectrumWidth = bars * (barWidth + gap) - gap
            val startX = thumbCenter.x - spectrumWidth / 2f
            
            for (i in 0 until bars) {
                val x = startX + i * (barWidth + gap)
                // Cheap pseudo-spectrum based on phase and index
                val hFactor = 0.3f + 0.7f * abs(Math.sin((phase * 2 * Math.PI) + i).toFloat())
                val barHeight = thumbRadius * 1.2f * hFactor
                
                drawRect(
                    color = Color.White,
                    topLeft = Offset(x, thumbCenter.y - barHeight / 2f),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}
