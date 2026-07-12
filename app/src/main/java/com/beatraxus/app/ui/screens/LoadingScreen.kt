package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * App-launch splash. Design language: rotating vinyl record + spectrum-style
 * loading ring, matching the WelcomeScreen brand gradient. Transition off
 * this screen is driven by real state (PlayerUiState.isLoadingLibrary),
 * not a fixed timer — it waits for the library/main screen to actually be
 * ready, with a sane minimum display time and a safety-timeout fallback.
 */
@Composable
fun LoadingScreen(
    viewModel: PlayerViewModel,
    onLoadingFinished: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val infinite = rememberInfiniteTransition(label = "loadingLoop")

    // Vinyl rotation — slow, continuous, like a record actually spinning
    val vinylRotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "vinylRotation"
    )

    // Sweep phase for the spectrum ring — the actual "loading" indicator
    val sweepPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepPhase"
    )

    val entrance = remember { Animatable(0f) }

    // ---- Real readiness tracking (mirrors the hasStartedScanning pattern
    // already used in WelcomeScreen) instead of guessing with a delay. ----
    var hasLibraryLoadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoadingLibrary) {
        if (uiState.isLoadingLibrary) hasLibraryLoadStarted = true
    }

    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(500, easing = FastOutSlowInEasing))

        val minDisplayMs = 1400L   // keeps the animation from flashing on fast devices
        val maxWaitMs = 6000L      // safety net so a stuck state can never trap the user
        val pollMs = 80L
        var elapsed = 0L

        while (elapsed < maxWaitMs) {
            // Ready when: a library load actually ran and finished, OR the
            // library already had songs going into this screen (the
            // first-run path, where WelcomeScreen's own scan already
            // populated it), OR a terminal state (no permission / no
            // library) means there's nothing left to wait for.
            val libraryFinishedLoading = hasLibraryLoadStarted && !uiState.isLoadingLibrary
            val alreadyPopulated = !uiState.isLoadingLibrary && uiState.scanCount > 0
            val terminalState = uiState.permissionDenied || uiState.showScanOptions

            if ((libraryFinishedLoading || alreadyPopulated || terminalState) && elapsed >= minDisplayMs) {
                break
            }
            delay(pollMs)
            elapsed += pollMs
        }
        onLoadingFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF060608), Color(0xFF0E0E14), Color(0xFF060608)))
            ),
        contentAlignment = Alignment.Center
    ) {
        PremiumGlows(infinite) // reuses the same ambient glow as WelcomeScreen

        Column(
            modifier = Modifier.graphicsLayer {
                alpha = entrance.value
                scaleX = 0.92f + entrance.value * 0.08f
                scaleY = 0.92f + entrance.value * 0.08f
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(196.dp)) {

                // ---- Spectrum / VU sweep ring (the loading indicator) ----
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val segments = 40
                    val radius = min(size.width, size.height) / 2f - 6.dp.toPx()
                    val tickLen = 10.dp.toPx()
                    for (i in 0 until segments) {
                        val angle = (360f / segments) * i
                        var diff = ((angle - sweepPhase + 540f) % 360f) - 180f
                        diff = kotlin.math.abs(diff)
                        val proximity = (1f - (diff / 55f)).coerceIn(0f, 1f)
                        val alpha = 0.12f + proximity * 0.88f
                        val len = tickLen * (0.6f + proximity * 0.4f)
                        rotate(angle, pivot = center) {
                            drawLine(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF7C4DFF), Color(0xFF1E88E5))
                                ),
                                start = Offset(center.x, center.y - radius),
                                end = Offset(center.x, center.y - radius + len),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                alpha = alpha
                            )
                        }
                    }
                }

                // ---- Vinyl record ----
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .graphicsLayer { rotationZ = vinylRotation }
                        .clip(CircleShape)
                        .background(Color(0xFF16161F)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Grooves
                        val grooveCount = 7
                        for (g in 1..grooveCount) {
                            val r = (size.minDimension / 2f) * (g / (grooveCount + 1.5f))
                            drawCircle(
                                color = Color.White.copy(alpha = 0.05f),
                                radius = r,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                        // Faint reflection streak for a "pressed vinyl" feel
                        drawArc(
                            color = Color.White.copy(alpha = 0.04f),
                            startAngle = -40f,
                            sweepAngle = 50f,
                            useCenter = false,
                            style = Stroke(width = size.minDimension * 0.28f)
                        )
                    }

                    // Label with the brand mark, scaled down
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF1E88E5)))),
                        contentAlignment = Alignment.Center
                    ) {
                        BrandMark(sizeDp = 34.dp)
                    }

                    // Spindle hole
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF060608))
                    )
                }

                // ---- Tonearm resting on the record (static, decorative) ----
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pivot = Offset(size.width * 0.86f, size.height * 0.10f)
                    val tip = Offset(size.width * 0.60f, size.height * 0.42f)
                    val armPath = Path().apply {
                        moveTo(pivot.x, pivot.y)
                        lineTo(tip.x, tip.y)
                    }
                    drawCircle(color = Color.White.copy(alpha = 0.5f), radius = 5.dp.toPx(), center = pivot)
                    drawPath(
                        path = armPath,
                        color = Color.White.copy(alpha = 0.5f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = 3.5.dp.toPx(), center = tip)
                }
            }

            Spacer(Modifier.height(30.dp))

            Text(
                text = "BEATRAXUS",
                style = TextStyle(
                    color = Color.White, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 7.sp
                )
            )
            Text(
                text = "CALIBRATING BIT-PERFECT PATH",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 2.5.sp
                ),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
