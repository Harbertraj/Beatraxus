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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

/**
 * Glass Tube Seekbar: a translucent glass cylinder filled with liquid + bubbles,
 * with a faceted gem thumb.
 */
@Composable
fun GlassTubeSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color(0xFF29E0F5),
    inactiveColor: Color = Color.White.copy(alpha = 0.15f),
    seed: Int = 0
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val bubbles = remember(seed) {
        val r = Random(seed)
        List(18) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat() * 0.5f + 0.3f) }
    }

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
            val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
            val tubeHeight = height * 0.5f
            val cornerRadius = CornerRadius(tubeHeight / 2f, tubeHeight / 2f)

            // Glass tube outline (empty)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                topLeft = Offset(0f, centerY - tubeHeight / 2f),
                size = Size(width, tubeHeight),
                cornerRadius = cornerRadius
            )
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - tubeHeight / 2f),
                size = Size(width, tubeHeight),
                cornerRadius = cornerRadius,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Liquid fill
            val fillWidth = (displayProgress * width).coerceAtLeast(tubeHeight)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(activeColor.copy(alpha = 0.85f), activeColor)
                ),
                topLeft = Offset(0f, centerY - tubeHeight / 2f),
                size = Size(fillWidth, tubeHeight),
                cornerRadius = cornerRadius
            )

            // Bubbles inside the liquid
            bubbles.forEach { (bx, by, bs) ->
                val x = bx * fillWidth
                if (x < fillWidth - tubeHeight / 2f) {
                    val y = centerY - tubeHeight / 2f + by * tubeHeight
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f * bs),
                        radius = 2.5.dp.toPx() * bs,
                        center = Offset(x, y)
                    )
                }
            }

            // Top glass highlight
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(0f, centerY - tubeHeight / 2f + 2.dp.toPx()),
                size = Size(width, tubeHeight / 4f),
                cornerRadius = CornerRadius(tubeHeight / 4f, tubeHeight / 4f)
            )

            // Gem thumb
            val thumbX = displayProgress * width
            val thumbR = tubeHeight * 0.62f
            for (i in 3 downTo 1) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.10f * i),
                    radius = thumbR + i * 3.dp.toPx(),
                    center = Offset(thumbX, centerY)
                )
            }
            val diamond = androidx.compose.ui.graphics.Path().apply {
                moveTo(thumbX, centerY - thumbR)
                lineTo(thumbX + thumbR, centerY)
                lineTo(thumbX, centerY + thumbR)
                lineTo(thumbX - thumbR, centerY)
                close()
            }
            drawPath(diamond, brush = Brush.linearGradient(
                listOf(Color.White, activeColor, activeColor.copy(alpha = 0.7f))
            ))
            drawPath(diamond, color = Color.White.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
