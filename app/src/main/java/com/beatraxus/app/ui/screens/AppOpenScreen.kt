package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Process-wide flag: the app-open screen is shown once per cold start, never again until the process dies. */
object AppOpenSplashState {
    @Volatile
    var shown: Boolean = false
}

/** Total time the app-open screen is on screen, including its closing fade. */
const val APP_OPEN_DURATION_MS = 4000

/** Length of the closing fade-out played by the caller once [AppOpenScreen] reports it is finished. */
const val APP_OPEN_EXIT_FADE_MS = 350

// Timeline (ms from the first frame). Every animated value below is a pure function of this clock,
// so nothing can pop or restart; the whole screen is one continuous 4s timeline.
private const val ORBIT_START_MS = 500f   // the logo sits still for 0.5s, then the orbit begins
private const val ORBIT_INTRO_MS = 800f   // glow + swell ease in over this long, so the start is soft
private const val BEAT_MS = 1000f         // one swell + one new ring per beat
private const val RING_LIFE_MS = 2400f    // each ring travels outward and fades over this long
private const val RING_COUNT = 4          // rings spawned at 0.5s, 1.5s, 2.5s, 3.5s

// Brand colours sampled from the app icon (cyan -> violet -> magenta).
private val LogoCyan = Color(0xFF22D3FF)
private val LogoViolet = Color(0xFF7B5CFF)
private val LogoMagenta = Color(0xFFE040FB)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Fades a layer in and lifts it a few dp, starting at [startMs] on the screen clock. */
private fun Modifier.revealAt(
    time: () -> Float,
    startMs: Float,
    durationMs: Float = 650f,
    rise: Dp = 8.dp
): Modifier = graphicsLayer {
    val p = smoothstep(startMs, startMs + durationMs, time())
    alpha = p
    translationY = rise.toPx() * (1f - p)
}

/**
 * App-open screen shown on a cold start (after the app was fully closed).
 *
 * It continues the system splash without a jump: same background colour and the same Beatraxus
 * logo at the exact centre of the screen, at the same size. For the first 0.5s the logo simply
 * holds still (this also hides any frame drops while the library and main UI are being set up).
 * Then the orbit starts: a soft glow fades in, the logo begins to breathe, and thin gradient rings
 * drift outward from it one per beat. Under the logo the name and a short, factual description of
 * the app fade in. The caller fades the screen out; it is fully gone after [APP_OPEN_DURATION_MS].
 *
 * Performance: the clock is read only inside draw / graphicsLayer blocks, so the screen is composed
 * once and never recomposed while animating, and brushes are created once instead of every frame.
 */
@Composable
fun AppOpenScreen(onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)

    // Elapsed milliseconds since the screen appeared. Linear and time-based: if a frame is late the
    // animation skips ahead instead of slowing down.
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            clock.animateTo(
                targetValue = APP_OPEN_DURATION_MS.toFloat(),
                animationSpec = tween(APP_OPEN_DURATION_MS, easing = LinearEasing)
            )
        }
        // Hand over to the caller early enough that its fade-out ends exactly at the 4s mark.
        delay((APP_OPEN_DURATION_MS - APP_OPEN_EXIT_FADE_MS).toLong())
        finished()
    }
    val time: () -> Float = { clock.value }

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
        // Glow + orbit rings behind the logo.
        Box(
            modifier = Modifier
                .size(320.dp)
                .drawWithCache {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val base = 60.dp.toPx() // half of the 120dp logo
                    val glowRadius = base * 2.3f
                    val glowBrush = Brush.radialGradient(
                        colors = listOf(LogoViolet, Color.Transparent),
                        center = c,
                        radius = glowRadius
                    )
                    val ringBrush = Brush.sweepGradient(
                        colors = listOf(LogoCyan, LogoViolet, LogoMagenta, LogoCyan),
                        center = c
                    )
                    val ringStroke = Stroke(width = 1.5.dp.toPx())

                    onDrawBehind {
                        val orbitT = time() - ORBIT_START_MS
                        if (orbitT <= 0f) return@onDrawBehind // still holding: identical to the system splash

                        val intro = smoothstep(0f, ORBIT_INTRO_MS, orbitT)
                        val beat = 0.5f - 0.5f * cos(2f * PI.toFloat() * orbitT / BEAT_MS)

                        drawCircle(
                            brush = glowBrush,
                            radius = glowRadius,
                            center = c,
                            alpha = (0.14f + 0.12f * beat) * intro
                        )

                        for (n in 0 until RING_COUNT) {
                            val age = orbitT - n * BEAT_MS
                            if (age <= 0f || age >= RING_LIFE_MS) continue
                            val life = age / RING_LIFE_MS
                            // Ease-out sine: moves steadily and settles gently, never "stalls".
                            val radius = base * (1.02f + 1.25f * sin(life * (PI.toFloat() / 2f)))
                            // Zero alpha at birth AND at death, so rings never pop in or out.
                            val alpha = smoothstep(0f, 0.12f, life) * (1f - life).pow(1.6f) * 0.45f
                            // Slow turn of the colour sweep gives the rings their orbiting feel.
                            rotate(degrees = orbitT * 0.03f + n * 40f, pivot = c) {
                                drawCircle(
                                    brush = ringBrush,
                                    radius = radius,
                                    center = c,
                                    alpha = alpha,
                                    style = ringStroke
                                )
                            }
                        }
                    }
                }
        )

        // The Beatraxus logo: static for 0.5s, then breathes gently with the beat.
        Image(
            painter = painterResource(R.drawable.beatraxus_logo),
            contentDescription = null,
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer {
                    val orbitT = time() - ORBIT_START_MS
                    if (orbitT > 0f) {
                        val intro = smoothstep(0f, ORBIT_INTRO_MS, orbitT)
                        val beat = 0.5f - 0.5f * cos(2f * PI.toFloat() * orbitT / BEAT_MS)
                        val s = 1f + 0.04f * beat * intro
                        scaleX = s
                        scaleY = s
                    }
                }
        )

        // Text block. The top half is an empty spacer, so the block starts exactly at the screen
        // centre line and the logo above it stays perfectly centred (matches the system splash).
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 92.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Start padding equals the letter spacing so the tracked text stays optically centred.
                Text(
                    text = "BEATRAXUS",
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 8.sp,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .revealAt(time, startMs = 250f)
                )
                Text(
                    text = "HI-RES AUDIO PLAYER",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 3.sp,
                    modifier = Modifier
                        .padding(start = 3.dp, top = 14.dp)
                        .revealAt(time, startMs = 550f)
                )
                Text(
                    text = "Bit-perfect  ·  USB DAC  ·  Parametric EQ",
                    color = LogoCyan.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .padding(start = 1.dp, top = 8.dp)
                        .revealAt(time, startMs = 850f)
                )
            }
        }
    }
}
