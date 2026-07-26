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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Crystal Prism Seekbar: a rainbow gradient glass bar with a faceted
 * diamond thumb.
 */
@Composable
fun CrystalPrismSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.12f),
    seed: Int = 0
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var lastSeekTarget by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(progress) {
        if (lastSeekTarget != null && abs(progress - lastSeekTarget!!) < 0.01f) {
            lastSeekTarget = null
        }
    }

    val rainbow = listOf(
        Color(0xFFFF5A5A), Color(0xFFFFA84B), Color(0xFFFFF04B),
        Color(0xFF63E06B), Color(0xFF4BCBFF), Color(0xFF6B6BFF), Color(0xFFE05AFF)
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
            val displayProgress = draggingProgress ?: lastSeekTarget ?: progress
            val barHeight = height * 0.35f
            val corner = CornerRadius(barHeight / 2f, barHeight / 2f)

            // Dim glass track
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - barHeight / 2f),
                size = Size(width, barHeight),
                cornerRadius = corner
            )

            // Rainbow fill up to progress
            val fillWidth = (displayProgress * width).coerceAtLeast(barHeight)
            drawRoundRect(
                brush = Brush.horizontalGradient(rainbow),
                topLeft = Offset(0f, centerY - barHeight / 2f),
                size = Size(fillWidth, barHeight),
                cornerRadius = corner,
                alpha = 0.9f
            )
            // Glass shine
            drawRoundRect(
                color = Color.White.copy(alpha = 0.2f),
                topLeft = Offset(0f, centerY - barHeight / 2f + 1.5.dp.toPx()),
                size = Size(fillWidth, barHeight / 3f),
                cornerRadius = CornerRadius(barHeight / 4f, barHeight / 4f)
            )

            // Faceted diamond thumb
            val thumbX = displayProgress * width
            val r = barHeight * 0.85f
            for (i in 3 downTo 1) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f * i),
                    radius = r + i * 3.dp.toPx(),
                    center = Offset(thumbX, centerY)
                )
            }
            val top = Offset(thumbX, centerY - r)
            val bottom = Offset(thumbX, centerY + r)
            val left = Offset(thumbX - r, centerY)
            val right = Offset(thumbX + r, centerY)
            val mid = centerY - r * 0.15f

            val facet1 = Path().apply { moveTo(left.x, left.y); lineTo(thumbX, mid); lineTo(top.x, top.y); close() }
            val facet2 = Path().apply { moveTo(right.x, right.y); lineTo(thumbX, mid); lineTo(top.x, top.y); close() }
            val facet3 = Path().apply { moveTo(left.x, left.y); lineTo(thumbX, mid); lineTo(right.x, right.y); lineTo(bottom.x, bottom.y); close() }

            drawPath(facet3, brush = Brush.linearGradient(listOf(Color(0xFF4BCBFF), Color(0xFFE05AFF))))
            drawPath(facet1, color = Color.White.copy(alpha = 0.85f))
            drawPath(facet2, color = Color.White.copy(alpha = 0.55f))
            val outline = Path().apply {
                moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(bottom.x, bottom.y); lineTo(left.x, left.y); close()
            }
            drawPath(outline, color = Color.White.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }
    }
}
