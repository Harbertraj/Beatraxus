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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Beatraxus Prism: a chain of diamonds. Played diamonds are filled with the album gradient and
 * ripple gently while playing, the current one is a larger spinning white gem, the rest are outlines.
 */
@Composable
fun BeatraxusPrismSeekBar(
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
    val loop by rememberLoop(isPlaying, 2200, "prism_loop")
    val dragAmt by animateFloatAsState(
        targetValue = if (st.dragging != null) 1f else 0f,
        animationSpec = tween(200), label = "prism_drag"
    )
    val path = remember { Path() }
    val twoPi = (2.0 * PI).toFloat()

    Box(modifier = modifier.fillMaxWidth().seekDrag(st, onProgressChange, onProgressFinished)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val p = st.display(progress)

            val s = (minOf(5.dp.toPx(), h / 4f)).coerceAtLeast(2.dp.toPx())
            val gap = 5.dp.toPx()
            val step = 2f * s + gap
            val n = ((w + gap) / step).toInt().coerceAtLeast(1)
            val used = n * step - gap
            val x0 = (w - used) / 2f + s
            val headX = p * w
            val cur = (p * n).toInt().coerceIn(0, n - 1)
            val denom = (n - 1).coerceAtLeast(1).toFloat()

            for (i in 0 until n) {
                val cx = x0 + i * step
                val d = (cx - headX) / w
                val swell = dragAmt * exp(-(d * d) / 0.004f)
                val isCur = i == cur
                val played = i <= cur
                val bob = if (isPlaying && played && !isCur) {
                    sin(loop * twoPi + i * 0.6f) * 2.dp.toPx() * ((i + 1f) / (cur + 1f))
                } else 0f
                val scale = (if (isCur) 1.55f else if (played) 1f else 0.72f) + 0.5f * swell
                val half = s * scale
                val ccy = cy + bob

                path.reset()
                path.moveTo(cx, ccy - half)
                path.lineTo(cx + half, ccy)
                path.lineTo(cx, ccy + half)
                path.lineTo(cx - half, ccy)
                path.close()

                if (isCur) {
                    val gr = half * 2.6f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(dominantColor.copy(alpha = 0.6f), Color.Transparent),
                            center = Offset(cx, ccy), radius = gr
                        ),
                        radius = gr, center = Offset(cx, ccy)
                    )
                    rotate(degrees = loop * 90f, pivot = Offset(cx, ccy)) {
                        drawPath(path, Color.White)
                    }
                } else if (played) {
                    drawPath(path, lerp(dominantColor, activeColor, i / denom))
                } else {
                    drawPath(path, inactiveColor, style = Stroke(width = 1.3.dp.toPx()))
                }
            }
        }
    }
}
