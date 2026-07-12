package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * App-launch splash. Every field here reuses the existing brand palette
 * (BgDeep / AccentBlue / 0xFF7C4DFF) and the existing PremiumGlows()
 * background so it reads as part of the same app as WelcomeScreen, not a
 * separate bolted-on screen.
 */
@Composable
fun LoadingScreen(onLoadingFinished: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "loadingLoop")

    // Slow ambient background pulse — mirrors WelcomeScreen's gradientShift
    val glowPulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse),
        label = "glowPulse"
    )

    // Progress ring rotation — continuous, professional "spinner" feel
    val ringRotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "ringRotation"
    )

    // Equalizer bars inside the emblem (the "music" half of the mark)
    val barPulse by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "barPulse"
    )

    // Entrance animation for the whole content block
    val entrance = remember { Animatable(0f) }
    // Determinate progress bar synced to the real minimum-display duration
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        progress.animateTo(1f, tween(1600, easing = FastOutSlowInEasing))
        delay(150)
        onLoadingFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF060608), Color(0xFF0E0E14), Color(0xFF060608))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Reuses the exact same premium background glow used on WelcomeScreen
        PremiumGlows(infinite)

        Column(
            modifier = Modifier.graphicsLayer {
                alpha = entrance.value
                scaleX = 0.92f + entrance.value * 0.08f
                scaleY = 0.92f + entrance.value * 0.08f
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ---------- Emblem: rotating ring + gradient badge + mark ----------
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {

                // Rotating progress ring
                Canvas(modifier = Modifier.fillMaxSize()) {
                    rotate(ringRotation) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF1E88E5),
                                    Color(0xFF7C4DFF),
                                    Color.Transparent
                                )
                            ),
                            startAngle = 0f,
                            sweepAngle = 300f,
                            useCenter = false,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                // Solid gradient badge
                Box(
                    modifier = Modifier
                        .size(124.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF7C4DFF), Color(0xFF1E88E5))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // The mark itself: a single flowing "wing/flame" arc (dragon)
                    // resting above a row of equalizer bars (music) — one
                    // cohesive glyph instead of two competing motifs.
                    Canvas(modifier = Modifier.size(72.dp)) {
                        val w = size.width
                        val h = size.height

                        // Wing / flame silhouette — symmetric, geometric, no
                        // literal cartoon dragon; reads as a clean logo mark.
                        val wing = Path().apply {
                            moveTo(w * 0.5f, h * 0.06f)
                            cubicTo(
                                w * 0.30f, h * 0.10f,
                                w * 0.08f, h * 0.28f,
                                w * 0.14f, h * 0.46f
                            )
                            cubicTo(
                                w * 0.20f, h * 0.40f,
                                w * 0.30f, h * 0.38f,
                                w * 0.38f, h * 0.42f
                            )
                            cubicTo(
                                w * 0.34f, h * 0.30f,
                                w * 0.40f, h * 0.18f,
                                w * 0.5f, h * 0.06f
                            )
                            close()
                        }
                        val wingMirror = Path().apply {
                            moveTo(w * 0.5f, h * 0.06f)
                            cubicTo(
                                w * 0.70f, h * 0.10f,
                                w * 0.92f, h * 0.28f,
                                w * 0.86f, h * 0.46f
                            )
                            cubicTo(
                                w * 0.80f, h * 0.40f,
                                w * 0.70f, h * 0.38f,
                                w * 0.62f, h * 0.42f
                            )
                            cubicTo(
                                w * 0.66f, h * 0.30f,
                                w * 0.60f, h * 0.18f,
                                w * 0.5f, h * 0.06f
                            )
                            close()
                        }
                        drawPath(wing, color = Color.White.copy(alpha = 0.95f))
                        drawPath(wingMirror, color = Color.White.copy(alpha = 0.95f))

                        // Equalizer bars — the "music" half, animated with barPulse
                        val barCount = 5
                        val barW = w * 0.07f
                        val gap = w * 0.045f
                        val totalW = barCount * barW + (barCount - 1) * gap
                        var x = (w - totalW) / 2f
                        val heights = listOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)
                        heights.forEach { hf ->
                            val barH = h * 0.34f * hf * barPulse
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.95f),
                                topLeft = Offset(x, h * 0.92f - barH),
                                size = Size(barW, barH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
                            )
                            x += barW + gap
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "BEATRAXUS",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 7.sp
                )
            )
            Text(
                text = "PRECISION AUDIO ENGINE",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp
                ),
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(36.dp))

            // ---------- Determinate progress bar ----------
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.value)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF7C4DFF), Color(0xFF1E88E5))
                            )
                        )
                )
            }
        }
    }
}
