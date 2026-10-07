package com.beatraxus.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatraxus.app.R
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.ui.theme.AccentBlueSoft
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

private val WelcomeBg = Color(0xFF07070D)
private val WelcomeAccent = AccentBlue
private val WelcomeAccentAlt = AccentBlueSoft
private val WelcomeSurface = Color.White.copy(alpha = 0.05f)
private val WelcomeHairline = Color.White.copy(alpha = 0.08f)

/**
 * First-run flow: intro -> library setup -> scanning.
 *
 * Minimal, single-accent design: flat dark surfaces, hairline borders, calm fade/slide reveals
 * (no bounce, shimmer, particles or typewriter text), accented only with the app's blue. All behaviour and navigation logic is
 * unchanged; only the presentation was redesigned.
 */
@Composable
fun WelcomeScreen(
    viewModel: PlayerViewModel,
    onEnterFlow: (onGranted: () -> Unit) -> Unit,
    onFinish: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var hasStartedScanning by rememberSaveable { mutableStateOf(false) }
    var isFinished by rememberSaveable { mutableStateOf(false) }

    // The welcome flow replaces the cold-start screen for this launch.
    LaunchedEffect(Unit) { AppOpenSplashState.shown = true }

    // Automatically transition to scanning screen based on state
    LaunchedEffect(uiState.isScanning, uiState.scanProgress) {
        if (uiState.isScanning || uiState.scanProgress > 0f) {
            hasStartedScanning = true
        }
    }

    // Effect to navigate when scanning finishes
    LaunchedEffect(hasStartedScanning, uiState.isScanning, uiState.scanProgress) {
        if (hasStartedScanning && !uiState.isScanning && uiState.scanProgress >= 0.99f) {
            isFinished = true
            // Give a small delay so user can see 100% for a moment
            delay(1000)
            onFinish()
        }
    }

    // Handle the case where first run is marked complete externally
    LaunchedEffect(uiState.isFirstRun) {
        if (!uiState.isFirstRun) {
            onFinish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WelcomeBg)
    ) {
        WelcomeAmbientGlow()

        AnimatedContent(
            targetState = when {
                uiState.isScanning || isFinished || uiState.scanProgress >= 0.99f -> 2
                uiState.showScanOptions -> 1
                else -> 0
            },
            transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
            label = "welcomeState",
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) { state ->
            when (state) {
                0 -> WelcomeIntro(
                    permissionDenied = uiState.permissionDenied,
                    onStart = {
                        if (!uiState.isScanning) {
                            viewModel.showScanOptions()
                        }
                    }
                )
                1 -> ScanSetup(
                    folders = uiState.musicFolders,
                    onFullScan = { onEnterFlow { viewModel.startFullScan() } },
                    onAddFolder = { onEnterFlow { viewModel.openFolderPicker() } },
                    onRemoveFolder = { viewModel.removeMusicFolder(it) },
                    onScanFolders = { onEnterFlow { viewModel.startAddedFoldersScan() } }
                )
                else -> ScanProgress(
                    title = uiState.errorMessage ?: "Syncing your library",
                    songs = uiState.scanCount,
                    albums = uiState.albumCount,
                    artists = uiState.artistCount,
                    progress = uiState.scanProgress
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen 1 - intro
// ---------------------------------------------------------------------------------------------

private data class WelcomeFeature(val icon: ImageVector, val title: String, val body: String)

@Composable
private fun WelcomeIntro(
    permissionDenied: Boolean,
    onStart: () -> Unit
) {
    val features = remember {
        listOf(
            WelcomeFeature(
                Icons.Rounded.GraphicEq,
                "Bit-perfect playback",
                "USB DAC and exclusive output with an untouched signal path."
            ),
            WelcomeFeature(
                Icons.Rounded.Tune,
                "Studio-grade DSP",
                "Parametric EQ, high-quality resampling and dither."
            ),
            WelcomeFeature(
                Icons.Rounded.LibraryMusic,
                "One unified library",
                "Local, cloud and combined sources in one place."
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Reveal(delayMs = 0) { BrandTile(tile = 88.dp) }

                Spacer(Modifier.height(24.dp))

                Reveal(delayMs = 120) {
                    Text(
                        text = "Beatraxus",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                Reveal(delayMs = 200) {
                    Text(
                        text = "Precision audio for listeners who notice everything.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                Spacer(Modifier.height(36.dp))

                Reveal(delayMs = 300, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(WelcomeSurface)
                            .border(1.dp, WelcomeHairline, RoundedCornerShape(20.dp))
                            .padding(vertical = 4.dp)
                    ) {
                        features.forEachIndexed { index, feature ->
                            FeatureRow(feature)
                            if (index < features.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .height(1.dp)
                                        .background(WelcomeHairline)
                                )
                            }
                        }
                    }
                }
            }
        }

        Reveal(delayMs = 420, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (permissionDenied) {
                    Text(
                        text = "Storage permission is required to build your library.",
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                WelcomePrimaryButton(
                    text = if (permissionDenied) "Grant permission" else "Get started",
                    onClick = onStart
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FeatureRow(feature: WelcomeFeature) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WelcomeAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = AccentBlueSoft,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = feature.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = feature.body,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen 2 - library setup
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanSetup(
    folders: List<String>,
    onFullScan: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onScanFolders: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                text = "Set up your library",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Choose where Beatraxus should look for your music.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
            )

            ScanOptionCard(
                title = "Scan this device",
                description = "Find every compatible audio file in your device storage.",
                icon = Icons.Rounded.Search,
                color = WelcomeAccent,
                onClick = onFullScan
            )

            Spacer(Modifier.height(12.dp))

            ScanOptionCard(
                title = "Choose folders",
                description = "Pick only the folders you want in your library.",
                icon = Icons.Rounded.CreateNewFolder,
                color = WelcomeAccentAlt,
                onClick = onAddFolder
            )

            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = "SELECTED FOLDERS",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                folders.forEach { folder ->
                    FolderRow(
                        name = folder.substringAfterLast("/"),
                        onRemove = { onRemoveFolder(folder) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        if (folders.isNotEmpty()) {
            WelcomePrimaryButton(
                text = if (folders.size == 1) "Scan 1 folder" else "Scan ${folders.size} folders",
                onClick = onScanFolders
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FolderRow(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WelcomeSurface)
            .border(1.dp, WelcomeHairline, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = WelcomeAccentAlt,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen 3 - scanning
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanProgress(
    title: String,
    songs: Int,
    albums: Int,
    artists: Int,
    progress: Float
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(WelcomeSurface)
                .border(1.dp, WelcomeHairline, RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Adding your music. This only takes a moment.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WelcomeStatItem(Icons.Rounded.MusicNote, songs.toString(), "Songs", AccentBlueSoft)
                WelcomeStatItem(Icons.Rounded.Album, albums.toString(), "Albums", AccentBlueSoft)
                WelcomeStatItem(Icons.Rounded.Person, artists.toString(), "Artists", AccentBlueSoft)
            }

            Spacer(Modifier.height(32.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = WelcomeAccent,
                trackColor = Color.White.copy(alpha = 0.1f)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------------------------

/** Slow, soft blue glow behind the content. One cheap infinite animation, nothing else. */
@Composable
private fun WelcomeAmbientGlow() {
    val transition = rememberInfiniteTransition(label = "welcomeGlow")
    val breathe by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowBreathe"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val topCenter = Offset(size.width * 0.5f, size.height * 0.2f)
        val topRadius = size.width * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(WelcomeAccent.copy(alpha = 0.2f * breathe), Color.Transparent),
                center = topCenter,
                radius = topRadius
            ),
            radius = topRadius,
            center = topCenter
        )
        val bottomCenter = Offset(size.width * 0.95f, size.height * 0.98f)
        val bottomRadius = size.width * 0.8f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(WelcomeAccentAlt.copy(alpha = 0.1f), Color.Transparent),
                center = bottomCenter,
                radius = bottomRadius
            ),
            radius = bottomRadius,
            center = bottomCenter
        )
    }
}

/** Fades and lifts its content in after [delayMs]. No overshoot, no bounce. */
@Composable
private fun Reveal(
    delayMs: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        visible = true
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "reveal"
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 16.dp.toPx()
        }
    ) {
        content()
    }
}

/** The Beatraxus app-icon logo (same artwork as the launcher icon and the app-open screen) over a soft glow. */
@Composable
private fun BrandTile(tile: Dp) {
    Box(
        modifier = Modifier.size(tile * 1.7f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7B5CFF).copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = r
                ),
                radius = r,
                center = center
            )
        }
        Image(
            painter = painterResource(R.drawable.beatraxus_logo),
            contentDescription = "Beatraxus",
            modifier = Modifier.size(tile * 1.2f)
        )
    }
}

@Composable
private fun WelcomePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "primaryButtonScale"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(WelcomeAccent, WelcomeAccentAlt)))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
fun ScanOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = tween(120),
        label = "scanCardScale"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.3f else 0.1f,
        animationSpec = tween(120),
        label = "scanCardBorder"
    )
    val shape = RoundedCornerShape(20.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(WelcomeSurface)
            .border(1.dp, Color.White.copy(alpha = borderAlpha), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp)
        )
    }
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
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}
