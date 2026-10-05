package com.beatraxus.app.ui.components.seekbars

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Beatraxus Comet: a thin track with a glowing comet as the playhead. The played part fades in
 * from nothing into a bright tail, and sparks fly off the comet while playing.
 */
@Composable
fun BeatraxusCometSeekBar(
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
    val loop by rememberLoop(isPlaying, 1500, "comet_loop")
    val dragAmt by animateFloatAsState(
        targetValue = if (st.dragging != null) 1f else 0f,
        animationSpec = tween(200), label = "comet_drag"
    )
    val seedPhase = (seed % 100) * 0.07f
    val sparks = 16

    Box(modifier = modifier.fillMaxWidth().seekDrag(st, onProgressChange, onProgressFinished)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val p = st.display(progress)
            val edge = 6.dp.toPx()
            val headX = (p * w).coerceIn(edge, (w - edge).coerceAtLeast(edge))

            // Base track + 10% tick dots
            drawLine(
                color = inactiveColor, start = Offset(0f, cy), end = Offset(w, cy),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
            )
            for (t in 1..9) {
                val tx = w * t / 10f
                if (tx > headX) {
                    drawCircle(inactiveColor, radius = 1.3.dp.toPx(), center = Offset(tx, cy - 7.dp.toPx()))
                }
            }

            // Fading comet tail (played part)
            val tail = Brush.horizontalGradient(
                colors = listOf(dominantColor.copy(alpha = 0.1f), dominantColor, activeColor),
                startX = 0f, endX = headX.coerceAtLeast(1f)
            )
            val lineW = (3f + 1.5f * dragAmt).dp.toPx()
            drawLine(
                brush = tail, start = Offset(0f, cy), end = Offset(headX, cy),
                strokeWidth = lineW * 3f, cap = StrokeCap.Round, alpha = 0.18f
            )
            drawLine(
                brush = tail, start = Offset(0f, cy), end = Offset(headX, cy),
                strokeWidth = lineW, cap = StrokeCap.Round
            )

            // Sparks flying off the head
            val tailLen = (84f + 50f * dragAmt).dp.toPx()
            for (k in 0 until sparks) {
                val ph = (loop + k / sparks.toFloat()) % 1f
                val px = headX - ph * tailLen
                if (px < 0f) continue
                val py = cy + sin(k * 2.17f + seedPhase) * h * 0.32f * ph
                val a = ((1f - ph) * 0.9f).coerceIn(0f, 1f)
                val rad = (2.2f * (1f - ph) + 0.6f).dp.toPx()
                drawCircle(
                    color = lerp(activeColor, dominantColor, ph).copy(alpha = a),
                    radius = rad, center = Offset(px, py)
                )
            }

            // Comet head
            val glowR = (20f + 8f * dragAmt).dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(activeColor.copy(alpha = 0.55f), dominantColor.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(headX, cy), radius = glowR
                ),
                radius = glowR, center = Offset(headX, cy)
            )
            drawCircle(Color.White, radius = (4.5f + 1.5f * dragAmt).dp.toPx(), center = Offset(headX, cy))
        }
    }
}
