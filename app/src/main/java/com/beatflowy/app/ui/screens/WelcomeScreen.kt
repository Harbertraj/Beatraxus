package com.beatflowy.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.*
import com.beatflowy.app.R
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(
    viewModel: PlayerViewModel,
    onEnterFlow: () -> Unit,
    onFinish: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showScanning by remember { mutableStateOf(false) }
    var startAnimation by remember { mutableStateOf(false) }

    // Reset showScanning if permission is denied
    LaunchedEffect(uiState.permissionDenied) {
        if (uiState.permissionDenied) {
            showScanning = false
        }
    }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.music_wave))
    val lottieProgress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        isPlaying = true
    )

    // Animated values for the reveal transition
    val introScale by animateFloatAsState(
        targetValue = if (startAnimation) 1.2f else 1.0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "introScale"
    )

    LaunchedEffect(Unit) {
        delay(2000) // 2s intro sequence as proposed
        startAnimation = true
    }

    // Effect to navigate when scanning finishes after button click
    LaunchedEffect(uiState.isScanning, uiState.scanProgress) {
        if (showScanning && uiState.scanProgress >= 1f && !uiState.isScanning) {
            delay(800) // Small delay for visual completion
            onFinish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0A0B), // Deep black/navy
                        Color(0xFF121217),
                        Color(0xFF0A0A0B)
                    )
                )
            )
    ) {
        // Overlay Gradient for that "Liquid" feel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x4D7C4DFF), // Translucent Purple
                            Color.Transparent
                        ),
                        radius = 2000f
                    )
                )
                .alpha(0.3f)
        )

        AnimatedContent(
            targetState = showScanning,
            transitionSpec = {
                fadeIn(tween(600)) togetherWith fadeOut(tween(600))
            },
            label = "screenState"
        ) { isScanning ->
            if (!isScanning) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 32.dp, top = 80.dp, end = 32.dp, bottom = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Title Group
                    Box(contentAlignment = Alignment.Center) {
                        // 2. Reveal Transition - Text
                        androidx.compose.animation.AnimatedVisibility(
                            visible = startAnimation,
                            enter = fadeIn(animationSpec = tween(1000)) +
                                    slideInVertically(
                                        initialOffsetY = { -60 }, // Slides in from further top
                                        animationSpec = tween(1000, easing = LinearOutSlowInEasing)
                                    )
                        ) {
                            // 5. Extra Micro Interaction (Breathing Effect)
                            val infiniteTransition = rememberInfiniteTransition(label = "breathing")
                            val breathingScale by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.02f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(2000, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "scale"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = breathingScale
                                    scaleY = breathingScale
                                }
                            ) {
                                Text(
                                    text = "Welcome, Audiophile",
                                    style = TextStyle(
                                        color = Color.White,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.08.sp
                                    )
                                )

                                // Subtle Glow/Gradient effect
                                Text(
                                    text = "Music Awakens",
                                    style = TextStyle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFFBDC3C7),
                                                Color(0xFF7C4DFF)
                                            ) // Silver to Vibrant Purple
                                        ),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Light,
                                        letterSpacing = 6.sp
                                    ),
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .alpha(0.8f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(450.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        // Music-themed animated phrases
                        var musicPhraseIndex by remember { mutableIntStateOf(0) }
                        val musicPhrases = listOf(
                            "Feel the Rhythm",
                            "Hear the Soul",
                            "Deep Bass Awaits",
                            "Crystal Clear Sound",
                            "Your Music, Evolved"
                        )

                        LaunchedEffect(startAnimation) {
                            if (startAnimation) {
                                while (true) {
                                    delay(3000)
                                    musicPhraseIndex = (musicPhraseIndex + 1) % musicPhrases.size
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = startAnimation,
                            enter = fadeIn(tween(1000, 800)) + expandVertically(tween(1000, 800)),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            AnimatedContent(
                                targetState = musicPhrases[musicPhraseIndex],
                                transitionSpec = {
                                    (slideInVertically { height -> height } + fadeIn()).togetherWith(
                                        slideOutVertically { height -> -height } + fadeOut()
                                    )
                                },
                                label = "musicPhrase"
                            ) { phrase ->
                                Text(
                                    text = phrase.uppercase(),
                                    style = TextStyle(
                                        color = Color(0xFF7C4DFF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 4.sp
                                    ),
                                    modifier = Modifier.alpha(0.9f)
                                )
                            }
                        }

                        // 3. Reveal Transition - Premium Start Button
                        androidx.compose.animation.AnimatedVisibility(
                            visible = startAnimation,
                            enter = fadeIn(tween(1200, 500)) + slideInVertically(
                                initialOffsetY = { 60 },
                                animationSpec = tween(1200, easing = LinearOutSlowInEasing)
                            )
                        ) {
                            // Pulsing glow for the button (Subtle Ambient Loop)
                            val infiniteTransition =
                                rememberInfiniteTransition(label = "buttonGlow")
                            val glowAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.4f,
                                targetValue = 0.8f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, easing = EaseInOutSine),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "glowAlpha"
                            )

                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        shadowElevation = 20f
                                        shape = RoundedCornerShape(32.dp)
                                        clip = true
                                        alpha = glowAlpha
                                    }
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF1E88E5), Color(0xFF7C4DFF))
                                        )
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            onEnterFlow()
                                            showScanning = true
                                        }
                                    )
                                    .padding(horizontal = 48.dp, vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "ENTER THE FLOW",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.5.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // Scanning Screen (Second Screen of Welcome)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(24.dp)
                            .shadow(40.dp, RoundedCornerShape(32.dp))
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xE6121216)) // Semi-transparent base
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(Color.White.copy(0.15f), Color.White.copy(0.05f))
                                ),
                                shape = RoundedCornerShape(32.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .blur(50.dp)
                        )

                        Column(
                            modifier = Modifier
                                .padding(vertical = 40.dp, horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Syncing Music...",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                letterSpacing = 0.5.sp
                            )

                            Spacer(Modifier.height(36.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                WelcomeStatItem(
                                    Icons.Rounded.MusicNote,
                                    uiState.scanCount.toString(),
                                    "Songs",
                                    Color(0xFFFF4081)
                                )
                                WelcomeStatItem(
                                    Icons.Rounded.Album,
                                    uiState.albumCount.toString(),
                                    "Albums",
                                    Color(0xFFB2FF59)
                                )
                                WelcomeStatItem(
                                    Icons.Rounded.Person,
                                    uiState.artistCount.toString(),
                                    "Artists",
                                    Color(0xFF7C4DFF)
                                )
                            }

                            Spacer(Modifier.height(40.dp))

                            LinearProgressIndicator(
                                progress = { uiState.scanProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(CircleShape),
                                color = AccentBlue,
                                trackColor = Color.White.copy(0.08f)
                            )

                            Spacer(Modifier.height(18.dp))

                            Text(
                                "${(uiState.scanProgress * 100).toInt()}%",
                                color = AccentBlue,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            label,
            color = Color.White.copy(0.5f),
            fontSize = 12.sp
        )
    }
}
