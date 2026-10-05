package com.beatraxus.app.ui.components.seekbars

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Beatraxus Thread: a glowing sound-wave thread. The played part is a flowing sine wave
 * that swells with the beat; the remaining part is a calm dashed line. An orb marks the playhead.
 */
@Composable
fun BeatraxusThreadSeekBar(
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
    val st = rememberSeekDragState(progress)
    val flow by rememberLoop(isPlaying, 1800, "thread_flow")
    val beat by rememberLoop(isPlaying, 1100, "thread_beat")
    val dragAmt by animateFloatAsState(
        targetValue = if (st.dragging != null) 1f else 0f,
        animationSpec = tween(200), label = "thread_drag"
    )
    val env = remember(seed) { beatraxusEnvelope(seed) }
    val wave = remember { Path() }
    val twoPi = (2.0 * PI).toFloat()

    Box(modifier = modifier.fillMaxWidth().seekDrag(st, onProgressChange, onProgressFinished)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val p = st.display(progress)
            val edge = 6.dp.toPx()
            val headX = (p * w).coerceIn(edge, (w - edge).coerceAtLeast(edge))

            val maxAmp = (h / 2f - 4.dp.toPx()).coerceAtLeast(2.dp.toPx())
            val amp = (maxAmp * (0.45f + 0.25f * sin(beat * twoPi) + 0.3f * dragAmt))
                .coerceIn(1.dp.toPx(), maxAmp)

            // Calm dashed remainder
            drawLine(
                color = inactiveColor,
                start = Offset(headX, cy),
                end = Offset(w, cy),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
            )

            // Flowing played wave
            val k = twoPi / 46.dp.toPx()
            val taperLen = 24.dp.toPx()
            val stepPx = 3.dp.toPx()
            wave.reset()
            var x = 0f
            var first = true
            while (x <= headX) {
                val taper = (x / taperLen).coerceIn(0f, 1f)
                val e = 0.6f + 0.4f * env[((x / w) * (env.size - 1)).toInt().coerceIn(0, env.size - 1)]
                val y = cy + sin(x * k - flow * twoPi) * amp * taper * e
                if (first) { wave.moveTo(x, y); first = false } else wave.lineTo(x, y)
                x += stepPx
            }
            val brush = Brush.horizontalGradient(
                colors = listOf(dominantColor, activeColor),
                startX = 0f,
                endX = headX.coerceAtLeast(1f)
            )
            drawPath(
                path = wave, brush = brush, alpha = 0.28f,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = wave, brush = brush,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Orb playhead
            val glowR = 16.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(dominantColor.copy(alpha = 0.6f), Color.Transparent),
                    center = Offset(headX, cy), radius = glowR
                ),
                radius = glowR, center = Offset(headX, cy)
            )
            if (isPlaying) {
                drawCircle(
                    color = activeColor.copy(alpha = (1f - beat) * 0.5f),
                    radius = 6.dp.toPx() + 8.dp.toPx() * beat,
                    center = Offset(headX, cy),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
            val r = (5f + 2.5f * dragAmt).dp.toPx()
            drawCircle(color = Color.White, radius = r, center = Offset(headX, cy))
            drawCircle(
                color = dominantColor, radius = r + 2.dp.toPx(),
                center = Offset(headX, cy), style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
