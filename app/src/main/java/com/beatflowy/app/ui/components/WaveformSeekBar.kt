package com.beatflowy.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

@Composable
fun WaveformSeekBar(
    progress: Float, // 0.0 to 1.0
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    barWidth: Float = 4f,
    gap: Float = 4f,
    seed: Int = 0
) {
    // Generate unique heights for this song based on the seed (song ID hash)
    val barHeights = remember(seed) {
        val random = Random(seed)
        List(100) { random.nextFloat() * 0.8f + 0.2f }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onProgressChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onProgressChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            
            val totalBars = (width / (barWidth + gap)).toInt()
            
            for (i in 0 until totalBars) {
                val x = i * (barWidth + gap)
                val barProgress = x / width
                val color = if (barProgress <= progress) activeColor else inactiveColor
                
                // Use the pre-generated heights, cycling through them
                val hFactor = barHeights[i % barHeights.size]
                val barHeight = height * hFactor
                
                drawRect(
                    color = color,
                    topLeft = Offset(x, centerY - barHeight / 2),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}
