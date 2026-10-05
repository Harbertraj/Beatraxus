package com.beatraxus.app.ui.components.seekbars

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Beatraxus Pulse - the exclusive Beatraxus seekbar.
 *
 * Signature look:
 *  - Track is a row of slanted "slash" segments (the Beatraxus beat-mark), mirrored around the centre line.
 *  - Played segments blend from the album's dominant colour to the active colour,
 *    with a light shimmer that sweeps along the played part while music plays.
 *  - Playhead is a thin light blade with diamond caps, a soft glow and a ripple ring
 *    that beats outward while playing.
 *  - While dragging, the segments near your finger swell up like a magnifier.
 */
@Composable
fun BeatraxusPulseSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.25f),
    seed: Int = 0,
    dominantColor: Color = Color(0xFFE23AF0),
    isPlaying: Boolean = false
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        val target = lastSeekTarget
        if (target != null && abs(progress - target) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnProgressFinished by rememberUpdatedState(onProgressFinished)

    // Animations only run while playing so the idle bar costs nothing.
    val infinite = rememberInfiniteTransition(label = "beatraxus_pulse")
    val shimmer by if (isPlaying) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "shimmer"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val ripple by if (isPlaying) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
            label = "ripple"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val dragAmount by animateFloatAsState(
        targetValue = if (draggingProgress != null) 1f else 0f,
        animationSpec = tween(220),
        label = "dragAmount"
    )

    // Stable pseudo-waveform envelope (0.2..1.0), unique per song.
    val envelope = remember(seed) {
        val p1 = (seed % 97) * 0.13f
        val p2 = (seed % 61) * 0.29f
        FloatArray(128) { i ->
            val a = sin(i * 0.23f + p1) * 0.55f
            val b = sin(i * 0.071f + p2) * 0.30f
            val c = sin(i * 0.61f + p1 * 2f) * 0.15f
            (0.2f + 0.8f * abs(a + b + c)).coerceIn(0.2f, 1f)
        }
    }

    val path = remember { Path() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val initial = (down.position.x / width).coerceIn(0f, 1f)
                    draggingProgress = initial
                    currentOnProgressChange(initial)

                    var lastX = down.position.x
                    drag(down.id) { change ->
                        lastX = change.position.x
                        val p = (lastX / width).coerceIn(0f, 1f)
                        draggingProgress = p
                        currentOnProgressChange(p)
                        change.consume()
                    }
                    val finalProgress = (lastX / width).coerceIn(0f, 1f)
                    draggingProgress = null
                    lastSeekTarget = finalProgress
                    currentOnProgressFinished(finalProgress)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f

            val displayProgress = (draggingProgress ?: lastSeekTarget ?: progress).coerceIn(0f, 1f)

            val segW = 3.dp.toPx()
            val gap = 3.dp.toPx()
            val slant = 3.dp.toPx()
            val step = segW + gap
            val count = ((w - slant) / step).toInt().coerceAtLeast(1)

            val edge = 5.dp.toPx()
            val headX = (displayProgress * w).coerceIn(edge, (w - edge).coerceAtLeast(edge))

            val minH = 4.dp.toPx()
            val maxH = h * 0.74f

            for (i in 0 until count) {
                val x = i * step
                val cx = x + segW / 2f + slant / 2f
                val frac = i.toFloat() / count
                val played = cx <= headX

                val base = envelope[i % envelope.size]

                // Magnifier swell around the finger while dragging.
                val d = (cx - headX) / w
                val swell = dragAmount * exp(-(d * d) / 0.003f)
                // Gentle beat just behind the playhead while playing.
                val beat = if (isPlaying && played) {
                    val dd = (headX - cx) / w
                    0.18f * exp(-(dd * dd) / 0.0015f) *
                            (0.5f + 0.5f * sin(ripple * 2f * PI.toFloat()))
                } else 0f

                val bh = (minH + (maxH - minH) * base * (1f + 0.45f * swell + beat))
                    .coerceIn(minH, h)

                val color = if (played) {
                    val grad = lerp(dominantColor, activeColor, frac.coerceIn(0f, 1f))
                    // Shimmer sweeps across the played region.
                    val sweepPos = shimmer * displayProgress
                    val dist = abs(frac - sweepPos)
                    val glint = if (isPlaying) exp(-(dist * dist) / 0.0009f) else 0f
                    lerp(grad, Color.White, (0.65f * glint).coerceIn(0f, 1f))
                } else {
                    inactiveColor
                }

                path.reset()
                path.moveTo(x + slant, cy - bh / 2f)
                path.lineTo(x + slant + segW, cy - bh / 2f)
                path.lineTo(x + segW, cy + bh / 2f)
                path.lineTo(x, cy + bh / 2f)
                path.close()
                drawPath(path, color)
            }

            // --- Playhead: glow, ripple, blade, diamond caps ---
            val glowR = 16.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(dominantColor.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(headX, cy),
                    radius = glowR
                ),
                radius = glowR,
                center = Offset(headX, cy)
            )

            if (isPlaying || dragAmount > 0f) {
                val rr = 6.dp.toPx() + 14.dp.toPx() * ripple
                drawCircle(
                    color = activeColor.copy(alpha = ((1f - ripple) * 0.55f).coerceIn(0f, 1f)),
                    radius = rr,
                    center = Offset(headX, cy),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            val capTop = 1.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(headX, capTop + 4.dp.toPx()),
                end = Offset(headX, h - capTop - 4.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )

            val dSize = (4f + 1.5f * dragAmount).dp.toPx()
            path.reset()
            path.moveTo(headX, capTop)
            path.lineTo(headX + dSize, capTop + dSize)
            path.lineTo(headX, capTop + dSize * 2f)
            path.lineTo(headX - dSize, capTop + dSize)
            path.close()
            drawPath(path, Color.White)

            path.reset()
            path.moveTo(headX, h - capTop)
            path.lineTo(headX + dSize, h - capTop - dSize)
            path.lineTo(headX, h - capTop - dSize * 2f)
            path.lineTo(headX - dSize, h - capTop - dSize)
            path.close()
            drawPath(path, Color.White)
        }
    }
}
