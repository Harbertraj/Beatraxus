package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Process-wide flag: the app-open screen is shown once per cold start, never again until the process dies. */
object AppOpenSplashState {
    @Volatile
    var shown: Boolean = false
}

/**
 * Minimal app-open screen shown on a cold start (after the app was fully closed).
 *
 * It continues the system splash without a jump: same background colour and the same five-bar
 * mark at the exact centre of the screen. The bars then breathe softly like an idle level meter
 * while the app name and then a short tagline ("Feel every beat") fade in and rise underneath, and
 * the whole screen is dismissed by the caller after roughly 1.5 seconds. No spinners.
 */
@Composable
fun AppOpenScreen(onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)

    val transition = rememberInfiniteTransition(label = "appOpen")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "appOpenPhase"
    )

    // 0f -> 1f progress for the app name and the tagline; each drives alpha + a short slide-up.
    val wordmarkAlpha = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(150)
        wordmarkAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(100)
        taglineAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(750)
        finished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070D))
            // Swallow touches so nothing underneath is tapped while this screen is up.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 108.dp, height = 144.dp)) {
            val barWidth = 12.dp.toPx()
            val step = 24.dp.toPx()
            val heights = floatArrayOf(56f, 104f, 144f, 104f, 56f)
            for (i in heights.indices) {
                val base = heights[i].dp.toPx()
                // Never taller than the resting shape, so it matches the system splash at its peak.
                val breathe = 0.88f + 0.12f * (0.5f + 0.5f * sin(phase - i * 0.9f))
                val barHeight = base * breathe
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(i * step, (size.height - barHeight) / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f)
                )
            }
        }

        Text(
            text = "BEATRAXUS",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 8.sp,
            modifier = Modifier
                .offset(y = 112.dp)
                .graphicsLayer {
                    alpha = wordmarkAlpha.value
                    translationY = 12.dp.toPx() * (1f - wordmarkAlpha.value)
                }
        )

        // App-related word under the name: fades in and rises slightly after the name.
        Text(
            text = "Feel every beat",
            color = Color(0xFFFFB300).copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 3.sp,
            modifier = Modifier
                .offset(y = 144.dp)
                .graphicsLayer {
                    alpha = taglineAlpha.value
                    translationY = 12.dp.toPx() * (1f - taglineAlpha.value)
                }
        )
    }
}
