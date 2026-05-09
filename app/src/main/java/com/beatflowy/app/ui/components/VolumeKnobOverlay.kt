package com.beatflowy.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatflowy.app.viewmodel.PlayerViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

@Composable
fun VolumeKnobOverlay(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    AnimatedVisibility(
        visible = uiState.showVolumeOverlay,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(32.dp)),
                color = Color(0xFF1A1A24).copy(alpha = 0.95f),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F2FF).copy(alpha = 0.2f))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Rounded.VolumeUp,
                        null,
                        tint = Color(0xFF00F2FF),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    // Use square-root mapping to get the linear slider position from squared dvcLevel
                    val sliderPosition = sqrt(uiState.dsp.config.dvcLevel)
                    
                    VolumeKnob(
                        value = sliderPosition,
                        onValueChange = viewModel::setSystemVolume
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "VOLUME ${(sliderPosition * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeKnob(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val sensitivity = 0.005f
    var dragAccumulator by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .size(160.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Use vertical drag delta
                        val delta = -dragAmount.y * sensitivity
                        val newValue = (value + delta).coerceIn(0f, 1f)
                        if (newValue != value) {
                            onValueChange(newValue)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 10.dp.toPx()
            
            // Background circle
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = radius,
                center = center
            )
            
            // Progress arc
            val sweepAngle = 270f
            val startAngle = 135f
            val currentSweep = value * sweepAngle
            
            drawArc(
                color = Color(0xFF00F2FF).copy(alpha = 0.2f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                topLeft = Offset(center.x - radius, center.y - radius)
            )
            
            drawArc(
                color = Color(0xFF00F2FF),
                startAngle = startAngle,
                sweepAngle = currentSweep,
                useCenter = false,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                topLeft = Offset(center.x - radius, center.y - radius)
            )
            
            // Knob pointer
            val angle = (startAngle + currentSweep) * (PI / 180f).toFloat()
            val pointerRadius = radius * 0.75f
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(
                    center.x + cos(angle) * pointerRadius,
                    center.y + sin(angle) * pointerRadius
                )
            )
        }
    }
}
