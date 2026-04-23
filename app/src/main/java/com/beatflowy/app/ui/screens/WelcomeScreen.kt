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
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.withTransform
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

    // Sequential Entry Timings
    var showFloatingElements by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }
    
    var thunderHitTrigger by remember { mutableStateOf(0) }
    val thunderHitScale = remember { Animatable(1f) }

    LaunchedEffect(thunderHitTrigger) {
        if (thunderHitTrigger > 0) {
            thunderHitScale.animateTo(1.15f, tween(100))
            thunderHitScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

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
        if (showScanning && uiState.scanCount > 0 && !uiState.isScanning) {
            delay(1500) // Give user time to see the results
            onFinish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060608),
                        lerp(Color(0xFF0E0E12), Color(0xFF15151D), gradientShift),
                        Color(0xFF060608)
                    )
                )
            )
    ) {
        // 0. Premium Background Glows
        PremiumGlows(infiniteTransition)

        // 1. Smooth Waveform Background (Liquid feel)
        WaveformBackground(infiniteTransition)

        // 2. Random Popping Music Icons
        if (showFloatingElements) {
            AttractedIconsBackground(
                thunderCenter = Offset(0.5f, 0.42f),
                onAbsorbed = { thunderHitTrigger++ }
            )
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
                                        initialOffsetY = { -200 },
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
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

                    // Thunder Electric Effect with Bigger Circle Background and Popping Icons
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .offset(y = 20.dp), // Move down a little
                        contentAlignment = Alignment.Center
                    ) {
                        val thunderPulse by infiniteTransition.animateFloat(
                            initialValue = 0.95f,
                            targetValue = 1.05f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(150, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "thunderPulse"
                        )
                        
                        val thunderAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "thunderAlpha"
                        )

                        val auraPulse by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.08f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, easing = EaseInOutSine),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "auraPulse"
                        )

                        // Launching animation values
                        val iconScale by animateFloatAsState(
                            targetValue = if (startAnimation) 1f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "iconLaunchScale"
                        )

                        val iconAlpha by animateFloatAsState(
                            targetValue = if (startAnimation) 1f else 0f,
                            animationSpec = tween(1000),
                            label = "iconLaunchAlpha"
                        )

                        // 1. Bigger Circle Background (Aura)
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .graphicsLayer {
                                    scaleX = iconScale * auraPulse
                                    scaleY = iconScale * auraPulse
                                    alpha = iconAlpha * 0.25f
                                }
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                                    ),
                                    CircleShape
                                )
                                .border(1.2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        )

                        // 2. Central Popping Icons
                        if (startAnimation) {
                            CentralPopIcons(infiniteTransition, iconAlpha)
                        }

                        // 3. Glow behind thunder
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .graphicsLayer { 
                                    scaleX = (thunderPulse * 1.7f) * iconScale
                                    scaleY = (thunderPulse * 1.7f) * iconScale
                                    alpha = (thunderAlpha * 0.45f) * iconAlpha
                                }
                                .background(Color(0xFF7C4DFF).copy(alpha = 0.5f), CircleShape)
                                .blur(40.dp)
                        )

                        // 4. Main Thunder Icon with Shape
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .graphicsLayer {
                                    scaleX = thunderPulse * iconScale * thunderHitScale.value
                                    scaleY = thunderPulse * iconScale * thunderHitScale.value
                                    alpha = thunderAlpha * iconAlpha
                                    shadowElevation = 30f
                                }
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FlashOn,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(90.dp)
                            )
                        }
                    }

                    // Centered Question Text (Typewriter Animation)
                    Box(
                        modifier = Modifier
                            .height(180.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        var showQuestion by remember { mutableStateOf(false) }
                        LaunchedEffect(startAnimation) {
                            if (startAnimation) {
                                delay(1000) // Start after thunder is fully visible
                                showQuestion = true
                            }
                        }

                        if (showQuestion) {
                            TypewriterText(
                                text = "Why did it take so long to install me?",
                                style = TextStyle(
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Light,
                                    letterSpacing = 1.2.sp
                                ),
                                delayMillis = 25L
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val phrasesColumnScope = this
                        // Music-themed animated phrases
                        var musicPhraseIndex by remember { mutableIntStateOf(0) }
                        val musicPhrases = listOf(
                            "Feel the Rhythm", "Hear the Soul", "Deep Bass Awaits",
                            "Crystal Clear Sound", "Your Music, Evolved",
                            "You are Magic", "Sonic Bliss", "Pure Audio"
                        )

                        LaunchedEffect(showTitle) {
                            if (showTitle) {
                                while (true) {
                                    delay(1000)
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
                                        initialOffsetY = { 300 },
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
                                        "Permissions are required to sync your library",
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

    val colors = remember {
        listOf(
            Color(0xFF1E88E5).copy(alpha = 0.4f),
            Color(0xFF7C4DFF).copy(alpha = 0.4f),
            Color(0xFF1E88E5).copy(alpha = 0.4f)
        )
    }
    val brush = remember(colors) { Brush.horizontalGradient(colors) }
    val wavePath = remember { androidx.compose.ui.graphics.Path() }

    Canvas(modifier = Modifier.fillMaxSize().alpha(0.2f)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        for (i in 0 until 4) {
            val waveOffset = i * PI.toFloat() / 2f
            val strokeWidth = (2.dp + i.dp).toPx()
            
            wavePath.reset()
            for (x in 0..width.toInt() step 12) { // Increased step further for performance
                val variation = sin(x * 0.004f + phase + waveOffset) * (50f + i * 10f)
                val secondaryVariation = sin(x * 0.008f - phase) * 20f
                val y = centerY + variation + secondaryVariation
                
                if (x == 0) wavePath.moveTo(0f, y) else wavePath.lineTo(x.toFloat(), y)
            }
            
            drawPath(
                path = wavePath,
                brush = brush,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

@Composable
fun AttractedIconsBackground(
    thunderCenter: Offset, // Normalized 0..1
    onAbsorbed: () -> Unit
) {
    val icons = listOf("♫", "♪", "♬", "♩", "🎶", "⚡", "✨", "🎵", "🎸", "🎹", "🎻", "🎷", "🎺")
    val particles = remember { mutableStateListOf<MovingParticle>() }
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTime ->
                // Spawn logic
                if (Random.nextFloat() < 0.15f && particles.size < 40) {
                    val side = Random.nextInt(4)
                    var startX = 0f
                    var startY = 0f
                    
                    when (side) {
                        0 -> { startX = Random.nextFloat(); startY = -0.1f } // Top
                        1 -> { startX = Random.nextFloat(); startY = 1.1f }  // Bottom
                        2 -> { startX = -0.1f; startY = Random.nextFloat() } // Left
                        3 -> { startX = 1.1f; startY = Random.nextFloat() }  // Right
                    }

                    particles.add(
                        MovingParticle(
                            x = startX,
                            y = startY,
                            icon = icons.random(),
                            speed = 0.002f + Random.nextFloat() * 0.004f,
                            drift = (Random.nextFloat() - 0.5f) * 0.001f,
                            scale = 0f,
                            targetScale = 0.5f + Random.nextFloat() * 0.5f,
                            rotation = Random.nextFloat() * 360f,
                            rotationSpeed = (Random.nextFloat() - 0.5f) * 4f,
                            phase = Random.nextFloat() * 2 * PI.toFloat()
                        )
                    )
                }

                // Update and filter in one pass if possible, or use an iterator
                val iterator = particles.iterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    val dxToCenter = thunderCenter.x - p.x
                    val dyToCenter = thunderCenter.y - p.y
                    val distSq = dxToCenter * dxToCenter + dyToCenter * dyToCenter
                    val distToCenter = sqrt(distSq.toDouble()).toFloat()
                    
                    p.x += (dxToCenter / distToCenter) * p.speed + sin(p.y * 5f + p.phase) * 0.001f
                    p.y += (dyToCenter / distToCenter) * p.speed + cos(p.x * 5f + p.phase) * 0.001f
                    p.rotation += p.rotationSpeed
                    
                    if (p.scale < p.targetScale) {
                        p.scale += 0.04f
                    }

                    if (distToCenter < 0.5f) {
                        val pull = 0.015f * (1f - distToCenter / 0.5f)
                        p.x += dxToCenter * pull
                        p.y += dyToCenter * pull
                        if (distToCenter < 0.2f) {
                            p.scale *= 0.92f
                        }
                    }

                    if (distToCenter < 0.04f || p.scale < 0.05f) {
                        iterator.remove()
                        if (distToCenter < 0.06f) onAbsorbed()
                    }
                }
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { p ->
            val x = p.x * size.width
            val y = p.y * size.height
            
            withTransform({
                translate(x, y)
                scale(p.scale, p.scale, Offset.Zero)
                rotate(p.rotation, Offset.Zero)
            }) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = p.icon,
                    style = TextStyle(
                        color = Color.White.copy(alpha = (p.scale * 0.2f).coerceAtMost(0.15f)),
                        fontSize = 24.sp
                    )
                )
            }
        }
    }
}

class MovingParticle(
    var x: Float,
    var y: Float,
    val icon: String,
    val speed: Float,
    val drift: Float,
    var scale: Float,
    val targetScale: Float,
    var rotation: Float,
    val rotationSpeed: Float,
    val phase: Float
)

@Composable
fun PremiumGlows(transition: InfiniteTransition) {
    val animValue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing)),
        label = "glowAnim"
    )

    Canvas(modifier = Modifier.fillMaxSize().alpha(0.4f)) {
        val width = size.width
        val height = size.height

        // Pre-calculating values for performance
        val glow1Center = Offset(
            width * (0.5f + 0.4f * sin(animValue).toFloat()),
            height * (0.3f + 0.2f * sin(animValue * 0.8f).toFloat())
        )
        val glow2Center = Offset(
            width * (0.2f + 0.3f * sin(animValue * 0.6f).toFloat()),
            height * (0.7f + 0.3f * sin(animValue * 1.1f).toFloat())
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF7C4DFF).copy(alpha = 0.15f), Color.Transparent),
                center = glow1Center,
                radius = 900f
            ),
            center = glow1Center,
            radius = 900f
        )
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF1E88E5).copy(alpha = 0.12f), Color.Transparent),
                center = glow2Center,
                radius = 1100f
            ),
            center = glow2Center,
            radius = 1100f
        )
    }
}

@Composable
fun FloatingMusicElements(floatAnim: Float) {
    // Kept for compatibility if needed, but no longer used in main layout
}

@Composable
fun CentralPopIcons(infiniteTransition: InfiniteTransition, parentAlpha: Float) {
    val icons = listOf("♫", "♪", "♬", "♩", "🎶", "⚡", "✨", "🎵")
    val density = LocalDensity.current
    
    Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        repeat(15) { i ->
            val duration = 1800 + i * 120
            val delay = i * 150
            
            val progress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, delayMillis = delay, easing = LinearOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pop-$i"
            )
            
            // Emitting icons in a radial pattern
            val startAngle = (i * (360f / 15f))
            val angle = (startAngle + (progress * 40f)) * (PI.toFloat() / 180f)
            
            // "move little distance" - float from center-out slightly
            val distancePx = with(density) { (50.dp + 40.dp * progress).toPx() }
            
            // Pop in and fade out logic
            val alpha = if (progress < 0.15f) progress * 6.6f 
                        else if (progress > 0.5f) (1f - progress) * 2f
                        else 1f
            
            val scale = 0.4f + (progress * 0.6f)
            
            Text(
                text = icons[i % icons.size],
                color = Color.White.copy(alpha = alpha * 0.8f * parentAlpha),
                fontSize = (16 + (i % 8)).sp,
                modifier = Modifier.graphicsLayer {
                    translationX = distancePx * cos(angle)
                    translationY = distancePx * sin(angle)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = progress * 120f
                }
            )
        }
    }
}

@Composable
fun TypewriterText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    delayMillis: Long = 50L
) {
    var textToDisplay by remember { mutableStateOf("") }
    
    LaunchedEffect(text) {
        textToDisplay = ""
        for (i in 1..text.length) {
            textToDisplay = text.substring(0, i)
            delay(delayMillis)
        }
    }

    Text(
        text = textToDisplay,
        style = style,
        modifier = modifier
    )
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
