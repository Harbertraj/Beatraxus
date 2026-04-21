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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.StrokeCap
import kotlin.random.Random
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.sin
import kotlin.math.PI
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.lerp
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

    // Automatically transition to scanning screen based on state
    LaunchedEffect(uiState.isScanning, uiState.permissionDenied) {
        if (uiState.isScanning) {
            showScanning = true
        } else if (uiState.permissionDenied) {
            showScanning = false
        }
    }

    // Concept: "Music Awakening" Animations
    val infiniteTransition = rememberInfiniteTransition(label = "musicAwakening")
    
    // 1. Background Gradient Pulse
    val gradientShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientShift"
    )

    // 2. Floating Elements Drift
    val floatAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "floatDrift"
    )

    // 3. Sequential Entry Timings
    var showFloatingElements by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        showFloatingElements = true
        delay(200)
        showTitle = true
        delay(300)
        showButton = true
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
                        Color(0xFF0A0A0B),
                        lerp(Color(0xFF121217), Color(0xFF1A1A22), gradientShift),
                        Color(0xFF0A0A0B)
                    )
                )
            )
    ) {
        // 1. Smooth Waveform Background (Liquid feel)
        WaveformBackground(infiniteTransition)

        // 2. Random Popping Music Icons
        if (showFloatingElements) {
            RandomPopIconsBackground()
        }

        // Overlay Gradient for that "Liquid" feel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x4D7C4DFF),
                            Color.Transparent
                        ),
                        radius = 2500f
                    )
                )
                .alpha(0.3f)
        )

        AnimatedContent(
            targetState = showScanning,
            transitionSpec = {
                fadeIn(tween(800)) togetherWith fadeOut(tween(800))
            },
            label = "screenState"
        ) { isScanning ->
            if (!isScanning) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val welcomeColumnScope = this
                    // Title Group
                    Box(contentAlignment = Alignment.Center) {
                        welcomeColumnScope.AnimatedVisibility(
                            visible = showTitle,
                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    slideInVertically(
                                        initialOffsetY = { -40 },
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                    )
                        ) {
                            val breathingScale by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.02f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(3000, easing = EaseInOutSine),
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

                                Text(
                                    text = "Music Awakens",
                                    style = TextStyle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFFBDC3C7), Color(0xFF7C4DFF))
                                        ),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Light,
                                        letterSpacing = 6.sp
                                    ),
                                    modifier = Modifier.padding(top = 8.dp).alpha(0.8f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(80.dp))

                    // Thunder Electric Effect
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val thunderPulse by infiniteTransition.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 1.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "thunderPulse"
                        )
                        
                        val thunderAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 0.9f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(50, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "thunderAlpha"
                        )

                        // Glow behind thunder
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .graphicsLayer { 
                                    scaleX = thunderPulse * 1.6f
                                    scaleY = thunderPulse * 1.6f
                                    alpha = thunderAlpha * 0.45f
                                }
                                .background(Color(0xFF7C4DFF).copy(alpha = 0.5f), CircleShape)
                                .blur(40.dp)
                        )

                        Icon(
                            imageVector = Icons.Rounded.FlashOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(140.dp)
                                .graphicsLayer {
                                    scaleX = thunderPulse
                                    scaleY = thunderPulse
                                    alpha = thunderAlpha
                                    shadowElevation = 25f
                                }
                        )
                    }

                    Spacer(modifier = Modifier.height(180.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val phrasesColumnScope = this
                        // Music-themed animated phrases
                        var musicPhraseIndex by remember { mutableIntStateOf(0) }
                        val musicPhrases = listOf(
                            "Feel the Rhythm", "Hear the Soul", "Deep Bass Awaits",
                            "Crystal Clear Sound", "Your Music, Evolved"
                        )

                        LaunchedEffect(showTitle) {
                            if (showTitle) {
                                while (true) {
                                    delay(4000)
                                    musicPhraseIndex = (musicPhraseIndex + 1) % musicPhrases.size
                                }
                            }
                        }

                        phrasesColumnScope.AnimatedVisibility(
                            visible = showTitle,
                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            AnimatedContent(
                                targetState = musicPhrases[musicPhraseIndex],
                                transitionSpec = {
                                    (slideInVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { it } + fadeIn()).togetherWith(
                                        slideOutVertically(animationSpec = spring()) { -it } + fadeOut()
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
                                    modifier = Modifier.alpha(0.7f)
                                )
                            }
                        }

                        // Premium Start Button
                        phrasesColumnScope.AnimatedVisibility(
                            visible = showButton,
                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessVeryLow)) +
                                    scaleIn(
                                        initialScale = 0.7f,
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                    ) +
                                    slideInVertically(
                                        initialOffsetY = { 60 },
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                    )
                        ) {
                            val buttonGlowAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.5f,
                                targetValue = 0.9f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(2000, easing = EaseInOutSine),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "buttonGlow"
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (uiState.permissionDenied) {
                                    Text(
                                        "Storage permission is required to find your music",
                                        color = Color(0xFFFF5252),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .graphicsLayer {
                                            shadowElevation = 20f
                                            shape = RoundedCornerShape(32.dp)
                                            clip = true
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
                                        .alpha(buttonGlowAlpha)
                                        .padding(horizontal = 48.dp, vertical = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (uiState.permissionDenied) "GRANT PERMISSION" else "ENTER THE FLOW",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 2.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Scanning Screen (Second Screen of Welcome) - Remastered with Glass Effect
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)), // Darken background slightly
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .wrapContentHeight()
                            .padding(20.dp)
                            .shadow(
                                elevation = 40.dp,
                                shape = RoundedCornerShape(32.dp),
                                clip = false,
                                ambientColor = Color.Black,
                                spotColor = Color(0xFF7C4DFF).copy(alpha = 0.5f)
                            )
                            .clip(RoundedCornerShape(32.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.08f),
                                        Color.White.copy(alpha = 0.02f)
                                    )
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.1f)
                                    )
                                ),
                                shape = RoundedCornerShape(32.dp)
                            )
                    ) {
                        // Glass Blur Layer
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color(0xCC121217)) // Dark shade for glass depth
                                .blur(60.dp) // Soften the background depth
                        )

                        Column(
                            modifier = Modifier
                                .padding(vertical = 44.dp, horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Animated Pulse for the "Syncing" text
                            val syncPulse by infiniteTransition.animateFloat(
                                initialValue = 0.7f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, easing = EaseInOutSine),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "syncPulse"
                            )

                            Text(
                                "Syncing Music...",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.alpha(syncPulse)
                            )

                            Spacer(Modifier.height(40.dp))

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

                            Spacer(Modifier.height(44.dp))

                            // Glow effect behind progress bar
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(14.dp)
                                        .blur(15.dp)
                                        .background(AccentBlue.copy(alpha = 0.2f), CircleShape)
                                )
                                
                                LinearProgressIndicator(
                                    progress = { uiState.scanProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(CircleShape),
                                    color = AccentBlue,
                                    trackColor = Color.White.copy(0.08f)
                                )
                            }

                            Spacer(Modifier.height(20.dp))

                            Text(
                                "${(uiState.scanProgress * 100).toInt()}%",
                                color = AccentBlue,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                style = TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = AccentBlue.copy(alpha = 0.5f),
                                        blurRadius = 10f
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WaveformBackground(transition: InfiniteTransition) {
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize().alpha(0.2f)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        val colors = listOf(
            Color(0xFF1E88E5).copy(alpha = 0.4f),
            Color(0xFF7C4DFF).copy(alpha = 0.4f),
            Color(0xFF1E88E5).copy(alpha = 0.4f)
        )

        for (i in 0 until 4) {
            val path = androidx.compose.ui.graphics.Path()
            val waveOffset = i * PI.toFloat() / 2f
            
            for (x in 0..width.toInt() step 4) {
                val variation = sin(x * 0.004f + phase + waveOffset) * (50f + i * 10f)
                val secondaryVariation = sin(x * 0.008f - phase) * 20f
                val y = centerY + variation + secondaryVariation
                
                if (x == 0) path.moveTo(0f, y) else path.lineTo(x.toFloat(), y)
            }
            
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(colors),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = (2.dp + i.dp).toPx(),
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

@Composable
fun RandomPopIconsBackground() {
    val icons = listOf(
        Icons.Rounded.MusicNote,
        Icons.Rounded.Album,
        Icons.Rounded.Person,
        Icons.Rounded.PlayArrow,
        Icons.Rounded.SkipNext,
        Icons.Rounded.GraphicEq,
        Icons.AutoMirrored.Rounded.VolumeUp,
        Icons.Rounded.Headset,
        Icons.Rounded.Mic,
        Icons.Rounded.Radio
    )

    Box(modifier = Modifier.fillMaxSize()) {
        repeat(12) { index ->
            PopIcon(icons = icons, index = index)
        }
    }
}

@Composable
fun BoxScope.PopIcon(icons: List<ImageVector>, index: Int) {
    var position by remember { mutableStateOf(Offset(Random.nextFloat(), Random.nextFloat())) }
    var icon by remember { mutableStateOf(icons.random()) }
    var isVisible by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        delay(index * 350L) // Staggered entry
        while (true) {
            position = Offset(Random.nextFloat(), Random.nextFloat())
            icon = icons.random()
            scale = 0.5f + Random.nextFloat() * 0.7f
            isVisible = true
            delay(1500 + Random.nextLong(1500))
            isVisible = false
            delay(1000 + Random.nextLong(2500))
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(1200)) + scaleIn(tween(1200), initialScale = 0.5f),
        exit = fadeOut(tween(1200)) + scaleOut(tween(1200), targetScale = 1.4f),
        modifier = Modifier
            .align(BiasAlignment(position.x * 2 - 1, position.y * 2 - 1))
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.12f),
            modifier = Modifier
                .size(32.dp)
                .blur(0.5.dp)
        )
    }
}

@Composable
fun FloatingMusicElements(floatAnim: Float) {
    // Kept for compatibility if needed, but no longer used in main layout
}

@Composable
fun WelcomeStatItem(
    icon: ImageVector,
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
