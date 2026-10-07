package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

/** Process-wide flag: the app-open screen is shown once per cold start, never again until the process dies. */
object AppOpenSplashState {
    @Volatile
    var shown: Boolean = false
}

// Brand colours sampled from the app icon (cyan -> violet -> magenta).
private val LogoCyan = Color(0xFF22D3FF)
private val LogoViolet = Color(0xFF7B5CFF)
private val LogoMagenta = Color(0xFFE040FB)

/**
 * App-open screen shown on a cold start (after the app was fully closed).
 *
 * It continues the system splash without a jump: same background colour and the same Beatraxus
 * app-icon logo at the exact centre of the screen, at the same size. The logo then "beats" like a
 * kick drum: it swells slightly and sends soft cyan/violet/magenta rings outward on every beat,
 * over a gently pulsing glow. Underneath, the app name, then a signature word and finally a
 * one-line promise fade in and rise. The caller dismisses the screen after roughly 2 seconds.
 */
@Composable
fun AppOpenScreen(onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)

    val transition = rememberInfiniteTransition(label = "appOpen")
    // One full beat: 0 -> 2*PI over 1.4s. Everything that "beats" is driven by this single value.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "appOpenPhase"
    )

    // 0f -> 1f progress; each drives alpha + a short slide-up (or, for rings, a fade-in).
    val ringsIn = remember { Animatable(0f) }
    val wordmarkAlpha = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    val promiseAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(150)
        wordmarkAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(100)
        taglineAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(750)
        finished()
    }
    // The promise line arrives just after the signature word has started rising in.
    LaunchedEffect(Unit) {
        delay(900)
        promiseAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    // Glow and rings ease in so the first frame matches the system splash exactly.
    LaunchedEffect(Unit) {
        ringsIn.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }

    // 0 at the start of each beat (so frame 0 equals the static splash), peaking mid-beat.
    val pulse = 0.5f - 0.5f * cos(phase)

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
        // Glow + beat rings behind the logo.
        Canvas(modifier = Modifier.size(320.dp)) {
            val c = center
            val base = 60.dp.toPx() // half of the 120dp logo
            val intro = ringsIn.value

            val glowRadius = base * 2.3f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        LogoViolet.copy(alpha = (0.18f + 0.14f * pulse) * intro),
                        Color.Transparent
                    ),
                    center = c,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = c
            )

            val turns = phase / (2f * PI.toFloat())
            for (k in 0..1) {
                val t = (turns + k * 0.5f) % 1f
                val e = FastOutSlowInEasing.transform(t)
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(LogoCyan, LogoViolet, LogoMagenta, LogoCyan),
                        center = c
                    ),
                    radius = base * (1.05f + 0.95f * e),
                    center = c,
                    alpha = (1f - t) * 0.4f * intro,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // The Beatraxus app-icon logo, swelling slightly on every beat.
        Image(
            painter = painterResource(R.drawable.beatraxus_logo),
            contentDescription = null,
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer {
                    val s = 1f + 0.05f * pulse
                    scaleX = s
                    scaleY = s
                }
        )

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

        // Signature word under the name: fades in and rises slightly after the name.
        Text(
            text = "SOUND, REIMAGINED",
            color = Color(0xFFFFB300).copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp,
            modifier = Modifier
                .offset(y = 144.dp)
                .graphicsLayer {
                    alpha = taglineAlpha.value
                    translationY = 12.dp.toPx() * (1f - taglineAlpha.value)
                }
        )

        // One-line promise about the app, arriving just after the signature word.
        Text(
            text = "Where every beat finds its orbit.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .offset(y = 168.dp)
                .graphicsLayer {
                    alpha = promiseAlpha.value
                    translationY = 12.dp.toPx() * (1f - promiseAlpha.value)
                }
        )
    }
}
