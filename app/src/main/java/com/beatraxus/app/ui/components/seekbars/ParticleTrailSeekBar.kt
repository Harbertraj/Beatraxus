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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float, // 1.0 to 0.0
    var color: Color
)

/**
 * Particle Trail Seekbar: thumb emits fading particles while dragging.
 */
@Composable
fun ParticleTrailSeekBar(
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
    
    val particles = remember { mutableStateListOf<Particle>() }
    val maxParticles = 50
    
    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    // Particle update loop
    LaunchedEffect(Unit) {
        val random = Random(seed)
        while (true) {
            withFrameMillis { _ ->
                val iterator = particles.iterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    p.x += p.vx
                    p.y += p.vy
                    p.life -= 0.02f
                    if (p.life <= 0) iterator.remove()
                }
                
                // Add new particles if dragging
                draggingProgress?.let { prog ->
                    if (particles.size < maxParticles) {
                        // This part is a bit tricky since we don't have the Canvas size here easily
                        // We'll update coordinates in the Draw phase or pass them back
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(seed) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width
                    val height = size.height
                    
                    fun emit(x: Float, y: Float) {
                        if (particles.size < maxParticles) {
                            particles.add(Particle(
                                x = x,
                                y = y,
                                vx = (Random.nextFloat() - 0.5f) * 2f,
                                vy = (Random.nextFloat() - 0.5f) * 5f,
                                life = 1.0f,
                                color = dominantColor
                            ))
                        }
                    }

                    val initialProgress = (down.position.x / width).coerceIn(0f, 1f)
                    draggingProgress = initialProgress
                    onProgressChange(initialProgress)
                    emit(down.position.x, height / 2f)
                    
                    var lastPos = down.position.x
                    drag(down.id) { change ->
                        lastPos = change.position.x
                        val newProgress = (lastPos / width).coerceIn(0f, 1f)
                        draggingProgress = newProgress
                        onProgressChange(newProgress)
                        emit(change.position.x, height / 2f)
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
            
            // Particles
            particles.forEach { p ->
                drawCircle(
                    color = p.color.copy(alpha = p.life),
                    radius = 3.dp.toPx() * p.life,
                    center = Offset(p.x, p.y)
                )
            }
            
            // Thumb
            drawCircle(
                color = activeColor,
                radius = 8.dp.toPx(),
                center = Offset(displayProgress * width, centerY)
            )
        }
    }
}
