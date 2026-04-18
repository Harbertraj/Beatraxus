package com.beatflowy.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun WavySlider(
    value: Float, // 0.0 to 1.0
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    waveAmplitude: Float = 12f,
    waveLength: Float = 80f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            val width = size.width
            val centerY = size.height / 2f
            val progressWidth = width * value

            // Draw Inactive Track (Flat or subtle wave)
            drawLine(
                color = inactiveColor,
                start = Offset(progressWidth, centerY),
                end = Offset(width, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Draw Active Wavy Track
            val path = Path()
            path.moveTo(0f, centerY)

            val step = 2f
            for (x in 0..progressWidth.toInt() step step.toInt()) {
                val xFloat = x.toFloat()
                // Wavy formula: sin((x / wavelength) * 2PI + phase) * amplitude
                val relativeX = xFloat / waveLength
                val y = centerY + sin(relativeX * 2 * PI.toFloat() - phase) * waveAmplitude
                path.lineTo(xFloat, y)
            }

            drawPath(
                path = path,
                color = activeColor,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )

            // Draw Thumb
            drawCircle(
                color = activeColor,
                radius = 8.dp.toPx(),
                center = Offset(
                    progressWidth,
                    centerY + sin((progressWidth / waveLength) * 2 * PI.toFloat() - phase) * waveAmplitude
                )
            )
        }
    }
}
