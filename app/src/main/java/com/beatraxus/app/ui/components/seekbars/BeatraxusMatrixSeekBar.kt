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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Beatraxus Matrix: an LED dot-matrix level display. Columns before the playhead glow in the album
 * gradient, the current column bounces like a live level meter, the rest stay dim.
 */
@Composable
fun BeatraxusMatrixSeekBar(
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
    val loop by rememberLoop(isPlaying, 700, "matrix_loop")
    val dragAmt by animateFloatAsState(
        targetValue = if (st.dragging != null) 1f else 0f,
        animationSpec = tween(200), label = "matrix_drag"
    )
    val env = remember(seed) { beatraxusEnvelope(seed) }
    val twoPi = (2.0 * PI).toFloat()
    val rows = 5

    Box(modifier = modifier.fillMaxWidth().seekDrag(st, onProgressChange, onProgressFinished)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val p = st.display(progress)

            val r = 1.7.dp.toPx()
            val colStep = 6.dp.toPx()
            val cols = (w / colStep).toInt().coerceAtLeast(1)
            val rowStep = minOf(6.dp.toPx(), ((h - 2f * r) / (rows - 1)).coerceAtLeast(2.dp.toPx()))
            val top = cy - rowStep * (rows - 1) / 2f
            val x0 = (w - cols * colStep) / 2f + colStep / 2f
            val headX = p * w
            val cur = (p * cols).toInt().coerceIn(0, cols - 1)
            val denom = (cols - 1).coerceAtLeast(1).toFloat()
            val dimInactive = inactiveColor.copy(alpha = inactiveColor.alpha * 0.35f)

            for (c in 0 until cols) {
                val cx = x0 + c * colStep
                val d = (cx - headX) / w
                val swell = dragAmt * exp(-(d * d) / 0.004f)
                val base = ceil(env[c % env.size] * rows).toInt().coerceIn(1, rows)
                val level = (base + (2f * swell).roundToInt()).coerceAtMost(rows)
                val isCur = c == cur
                val played = c < cur
                val litCount = if (isCur) {
                    if (isPlaying) 1 + ((0.5f + 0.5f * sin(loop * twoPi)) * (rows - 1)).roundToInt()
                    else rows
                } else level

                for (row in 0 until rows) {
                    val y = top + (rows - 1 - row) * rowStep
                    val lit = row < litCount
                    val color = when {
                        isCur -> if (lit) Color.White else dominantColor.copy(alpha = 0.25f)
                        played -> if (lit) lerp(dominantColor, activeColor, c / denom)
                                  else dominantColor.copy(alpha = 0.14f)
                        else -> if (lit) inactiveColor else dimInactive
                    }
                    if (isCur && lit) {
                        drawCircle(
                            color = dominantColor.copy(alpha = 0.35f),
                            radius = r * 2.6f, center = Offset(cx, y)
                        )
                    }
                    drawCircle(color = color, radius = r, center = Offset(cx, y))
                }
            }
        }
    }
}
