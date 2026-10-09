package com.beatraxus.app.ui.screens

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.RadioStation
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.toSong
import com.beatraxus.app.repository.RadioStationCache
import com.beatraxus.app.ui.components.InfoGlassBlock
import com.beatraxus.app.ui.components.InfoMetaRow
import com.beatraxus.app.ui.components.InfoStyleDialog
import com.beatraxus.app.ui.components.PipelineSignalPathSheet
import com.beatraxus.app.ui.utils.FastBlurTransformation
import com.beatraxus.app.ui.utils.LocalLiveAudioLevel
import com.beatraxus.app.ui.utils.RenderEffectHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** Accent shared with the Radio entry in the library drawer. */
private val RadioAccent = Color(0xFF00B8D4)

/**
 * Now Playing screen for live radio streams. It is shown instead of [NowPlayingScreen] whenever
 * the current song is a radio station (see `Song.isRadioStream`) and swaps back automatically as
 * soon as a normal song becomes current.
 *
 * It deliberately mirrors the song screen (same background modes, top bar, art card, title row,
 * controls pill and utility pill) so the two feel like one player. What differs is only what a
 * live stream cannot have: no seek bar / duration (a live level bar and "listening time" sit in
 * its place) and previous / next / shuffle step through stations instead of tracks. Queue is
 * replaced by a Stations list, and sleep timer, favourites, equalizer, station info, share and
 * the signal path are all available.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RadioNowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    uiState: PlayerUiState,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayStation: (Song) -> Unit,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onSetSleepTimer: (Int, Boolean, Int) -> Unit,
    onStopSleepTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appearance = uiState.appearance

    var showStations by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showStationInfo by remember { mutableStateOf(false) }
    var showPipeline by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val prevStation = RadioStationCache.neighbour(song.id, -1)
    val nextStation = RadioStationCache.neighbour(song.id, +1)

    // ---- Dominant colour of the station logo (used by the "Solid" background mode) ----
    var logoColor by remember(song.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(song.id, song.albumArtUri) {
        val uri = song.albumArtUri ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false)
            .size(100, 100)
            .precision(Precision.INEXACT)
            .build()
        val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        val rgb = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb
        if (rgb != null) logoColor = Color(rgb)
    }
    val dominantColor by animateColorAsState(
        targetValue = logoColor ?: Color(0xFF0E3A44),
        animationSpec = tween(600),
        label = "radioDominantColor"
    )

    // ---- "Listening time": the live-stream stand-in for the elapsed-time label ----
    var listenedSec by rememberSaveable(song.id) { mutableStateOf(0) }
    LaunchedEffect(song.id, isPlaying) {
        while (isPlaying) {
            delay(1000)
            listenedSec++
        }
    }

    // ---- Screen behind every popup is blurred + dimmed (same behaviour as the song screen) ----
    val strongOverlay = showSleepTimerSheet
    val lightOverlay = showPipeline || showStationInfo
    val overlayBlurActive = strongOverlay || lightOverlay
    val overlayBlurRadius by animateDpAsState(
        targetValue = when {
            strongOverlay -> 14.dp
            lightOverlay -> 24.dp
            else -> 0.dp
        },
        animationSpec = tween(280),
        label = "radioOverlayBlur"
    )
    val overlayDim by animateFloatAsState(
        targetValue = when {
            strongOverlay -> 0.42f
            lightOverlay -> 0.16f
            else -> 0f
        },
        animationSpec = tween(280),
        label = "radioOverlayDim"
    )
    val overlayScale by animateFloatAsState(
        targetValue = if (overlayBlurActive && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 0.97f else 1.0f,
        animationSpec = tween(250),
        label = "radioOverlayScale"
    )

    val backgroundBlurEffect = remember(appearance.nowPlayingBlurIntensity) {
        RenderEffectHelper.createBlurEffect(
            appearance.nowPlayingBlurIntensity,
            appearance.nowPlayingBlurIntensity
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow touches so nothing underneath the full-screen player reacts to them.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (overlayDim > 0f) {
                        drawRect(Color.Black.copy(alpha = overlayDim * 0.8f))
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = overlayDim * 0.5f)),
                                center = center,
                                radius = size.maxDimension * 0.75f
                            )
                        )
                    }
                }
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && overlayBlurRadius > 0.dp) {
                        Modifier.blur(radius = overlayBlurRadius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && overlayBlurActive) {
                        Modifier.graphicsLayer {
                            scaleX = overlayScale
                            scaleY = overlayScale
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            // ---- Background: same three modes as the song screen ----
            when (appearance.nowPlayingBackgroundMode) {
                NowPlayingBackgroundMode.BLACK -> Unit
                NowPlayingBackgroundMode.SOLID -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(dominantColor.copy(alpha = appearance.nowPlayingSolidColorIntensity))
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = appearance.nowPlayingSolidColorDarkness))
                    )
                }
                NowPlayingBackgroundMode.BLUR -> {
                    if (song.albumArtUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(song.albumArtUri)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .size(256, 256)
                                .precision(Precision.INEXACT)
                                .crossfade(true)
                                .apply {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                        transformations(FastBlurTransformation(appearance.nowPlayingBlurIntensity))
                                    }
                                }
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = 0.85f
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        renderEffect = backgroundBlurEffect
                                    }
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = appearance.nowPlayingBlurDarkness))
                    )
                }
            }

            // Soft radio-coloured glow so the screen still reads as "radio" in every mode.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(RadioAccent.copy(alpha = 0.20f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height * 0.30f),
                                radius = size.width * 0.95f
                            )
                        )
                    }
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.2f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f)
                            )
                        )
                    )
            )

            val dismissDrag = remember { mutableStateOf(0f) }

            Scaffold(
                modifier = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> dismissDrag.value += dragAmount },
                        onDragEnd = {
                            if (dismissDrag.value > 100) onClose()
                            dismissDrag.value = 0f
                        }
                    )
                },
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.systemBars,
                topBar = {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                        title = {
                            Text(
                                if (showStations) "Stations" else "Now Playing",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 22.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = Color.White,
                                modifier = Modifier.offset(y = (-8).dp)
                            )
                        },
                        navigationIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .offset(y = (-8).dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { if (showStations) showStations = false else onClose() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Close player",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        },
                        actions = {
                            Box(
                                Modifier
                                    .padding(end = 12.dp)
                                    .offset(y = (-8).dp)
                            ) {
                                if (!showStations) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .clickable { showStationInfo = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Info,
                                            contentDescription = "Station info",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                } else {
                                    // Sleep timer shortcut while the stations list is open (like the queue view)
                                    val timerActive = uiState.isSleepTimerActive
                                    Surface(
                                        onClick = { showSleepTimerSheet = true },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (timerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (timerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f)
                                        )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = if (timerActive) 10.dp else 8.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Timer,
                                                null,
                                                tint = if (timerActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            if (timerActive) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = fmtSleepTime(uiState.sleepTimerRemainingSeconds),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showStations) {
                        RadioStationsView(
                            song = song,
                            isPlaying = isPlaying,
                            onPlayStation = onPlayStation,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // ---------- Middle section: station logo in the same rounded art card ----------
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .offset(y = (-3).dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            ) {
                                val artShape = RoundedCornerShape(28.dp)
                                RadioPulseRings(active = isPlaying, shape = artShape)
                                RadioStationLogo(
                                    logoUrl = song.albumArtUri?.toString(),
                                    accent = RadioAccent,
                                    size = maxWidth,
                                    cornerRadius = 28.dp,
                                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), artShape)
                                )
                            }
                        }

                        // ---------- Metadata: badge, title / subtitle, favourite + options ----------
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(Modifier.height(36.dp), contentAlignment = Alignment.BottomCenter) {
                                    // Sits where the song screen shows the audio-quality badge;
                                    // long-press opens the signal path, exactly like the quality badge.
                                    RadioBadge(
                                        live = isPlaying,
                                        modifier = Modifier.combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {},
                                            onLongClick = { showPipeline = true }
                                        )
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        horizontalAlignment = Alignment.Start,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = song.title.trim().ifBlank { "Radio" },
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 24.sp,
                                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                                            ),
                                            color = Color.White,
                                            textAlign = TextAlign.Start,
                                            maxLines = 1,
                                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                        )
                                        Text(
                                            text = song.artist,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                color = Color.White.copy(alpha = 0.6f),
                                                lineHeight = 22.sp,
                                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                                            ),
                                            textAlign = TextAlign.Start,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.offset(y = 3.dp)
                                        )
                                    }

                                    // Favourite
                                    IconButton(onClick = onFavoriteClick) {
                                        Icon(
                                            if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                            tint = if (isFavorite) Color(0xFFFF4081) else Color.White.copy(0.7f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // Options (three dots)
                                    Box {
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(
                                                Icons.Rounded.MoreVert,
                                                contentDescription = "More options",
                                                tint = Color.White.copy(0.7f),
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                            modifier = Modifier.background(Color(0xFF1C1C1E))
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Station info", color = Color.White) },
                                                leadingIcon = { Icon(Icons.Rounded.Info, null, tint = Color.White.copy(0.8f)) },
                                                onClick = { showMenu = false; showStationInfo = true }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Share station", color = Color.White) },
                                                leadingIcon = { Icon(Icons.Rounded.Share, null, tint = Color.White.copy(0.8f)) },
                                                onClick = { showMenu = false; shareStation(context, song) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Copy stream link", color = Color.White) },
                                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = Color.White.copy(0.8f)) },
                                                onClick = {
                                                    showMenu = false
                                                    clipboard.setText(AnnotatedString(song.uri.toString()))
                                                    Toast.makeText(context, "Stream link copied", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Signal path", color = Color.White) },
                                                leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(0.8f)) },
                                                onClick = { showMenu = false; showPipeline = true }
                                            )
                                        }
                                    }
                                }
                            }

                            // ---------- Live bar + listening time (replaces seek bar + duration) ----------
                            Column(modifier = Modifier.fillMaxWidth()) {
                                RadioLiveBar(isPlaying = isPlaying, accent = RadioAccent)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = fmtListened(listenedSec),
                                        color = Color.White.copy(0.5f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(45.dp)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioEqBars(color = RadioAccent, animate = isPlaying, barCount = 5, barWidth = 3.dp, maxHeight = 14.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (isPlaying) "Streaming live" else "Paused",
                                            color = Color.White.copy(alpha = 0.55f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        text = "LIVE",
                                        color = RadioAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(45.dp),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }

                        // ---------- Controls pill (same shape / sizes as the song screen) ----------
                        val (prevIcon, playIcon, pauseIcon, nextIcon) = remember(appearance.nowPlayingIconStyle) {
                            transportIconsFor(appearance.nowPlayingIconStyle)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 0.dp)
                                .height(84.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .height(68.dp)
                                    .align(Alignment.Center)
                                    .shadow(8.dp, RoundedCornerShape(34.dp))
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(34.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(34.dp))
                                    .clip(RoundedCornerShape(34.dp))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Random station (the radio equivalent of shuffle)
                                    val canShuffle = RadioStationCache.stations.size >= 2
                                    IconButton(
                                        onClick = {
                                            RadioStationCache.stations
                                                .filter { "radio_${it.id}" != song.id }
                                                .randomOrNull()
                                                ?.let { onPlayStation(it.toSong()) }
                                        },
                                        enabled = canShuffle,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Shuffle,
                                            contentDescription = "Random station",
                                            tint = Color.White.copy(if (canShuffle) 0.5f else 0.2f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    // Previous station
                                    IconButton(
                                        onClick = { prevStation?.let { onPlayStation(it.toSong()) } },
                                        enabled = prevStation != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            prevIcon,
                                            contentDescription = "Previous station",
                                            tint = Color.White.copy(if (prevStation != null) 1f else 0.3f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // Placeholder for the elevated Play/Pause
                                    Spacer(modifier = Modifier.weight(1.6f))

                                    // Next station
                                    IconButton(
                                        onClick = { nextStation?.let { onPlayStation(it.toSong()) } },
                                        enabled = nextStation != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            nextIcon,
                                            contentDescription = "Next station",
                                            tint = Color.White.copy(if (nextStation != null) 1f else 0.3f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // Share (in the slot where the song screen has repeat)
                                    IconButton(onClick = { shareStation(context, song) }, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            Icons.Rounded.Share,
                                            contentDescription = "Share station",
                                            tint = Color.White.copy(0.5f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            Surface(
                                onClick = onPlayPause,
                                modifier = Modifier
                                    .size(72.dp)
                                    .shadow(16.dp, CircleShape, ambientColor = Color.Black.copy(0.5f)),
                                shape = CircleShape,
                                color = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isPlaying) pauseIcon else playIcon,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.Black,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }

                        // ---------- Utility pill ----------
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp, bottom = 24.dp)
                                .fillMaxWidth(0.95f)
                                .height(90.dp)
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UtilityItem(
                                    icon = Icons.Rounded.Timer,
                                    label = "Sleep Timer",
                                    isActive = uiState.isSleepTimerActive,
                                    onClick = { showSleepTimerSheet = true }
                                )
                                UtilityItem(
                                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                                    label = "Stations",
                                    isActive = showStations,
                                    onClick = { showStations = true },
                                    iconSize = 34.dp
                                )
                                UtilityItem(
                                    icon = Icons.Rounded.Equalizer,
                                    label = "Equalizer",
                                    isActive = uiState.dsp.config.eqEnabled,
                                    onClick = onOpenEqualizer
                                )
                                UtilityItem(
                                    icon = Icons.Rounded.GraphicEq,
                                    label = "Signal Path",
                                    isActive = showPipeline,
                                    onClick = { showPipeline = true }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- Popups (drawn above the blurred screen, like the song screen) ----------
        if (showSleepTimerSheet) {
            SleepTimerSheet(
                albumArtUri = song.albumArtUri,
                uiState = uiState,
                onSetTimer = onSetSleepTimer,
                onStopTimer = onStopSleepTimer,
                onDismiss = { showSleepTimerSheet = false },
                liveStream = true
            )
        }

        if (showStationInfo) {
            RadioStationInfoDialog(song = song, onDismiss = { showStationInfo = false })
        }

        if (showPipeline) {
            PipelineSignalPathSheet(
                song = song,
                uiState = uiState,
                onDismiss = { showPipeline = false },
                modifier = Modifier.fillMaxSize(),
                windowBlur = false // this screen already blurs itself
            )
        }
    }
}

/** "Queue" for radio: the station that is on air plus the stations that come next. */
@Composable
private fun RadioStationsView(
    song: Song,
    isPlaying: Boolean,
    onPlayStation: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val all = RadioStationCache.stations
    val currentStation = remember(song.id, all) {
        all.firstOrNull { "radio_${it.id}" == song.id } ?: RadioStation(
            id = song.id.removePrefix("radio_"),
            name = song.title,
            streamUrl = song.uri.toString(),
            country = song.artist.substringAfter("•", "").trim(),
            band = if (song.artist.startsWith("AM")) "AM" else "FM",
            favicon = song.albumArtUri?.toString()
        )
    }
    val upcoming = remember(song.id, all) {
        val idx = all.indexOfFirst { "radio_${it.id}" == song.id }
        val ordered = if (idx >= 0) all.drop(idx + 1) + all.take(idx) else all
        ordered
            .filter { "radio_${it.id}" != song.id }
            .distinctBy { it.id.ifBlank { it.streamUrl } }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "label_now") { RadioSectionLabel("NOW PLAYING") }
        item(key = "current") {
            RadioStationRow(
                station = currentStation,
                isCurrent = true,
                isPlaying = isPlaying,
                accent = RadioAccent,
                onClick = { onPlayStation(song) } // same id -> toggles play / pause
            )
        }
        item(key = "label_next") {
            RadioSectionLabel(if (upcoming.isEmpty()) "NO MORE STATIONS LOADED" else "UP NEXT  ·  ${upcoming.size}")
        }
        if (upcoming.isEmpty()) {
            item(key = "hint") {
                Text(
                    "Open Radio from the library menu to browse and load more stations.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        } else {
            items(upcoming, key = { it.id.ifBlank { it.streamUrl } }) { station ->
                RadioStationRow(
                    station = station,
                    isCurrent = false,
                    isPlaying = false,
                    accent = RadioAccent,
                    onClick = { onPlayStation(station.toSong()) }
                )
            }
        }
    }
}

@Composable
private fun RadioSectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun RadioStationInfoDialog(song: Song, onDismiss: () -> Unit) {
    val station = RadioStationCache.stations.firstOrNull { "radio_${it.id}" == song.id }
    val country = station?.country?.ifBlank { null } ?: song.artist.substringAfter("•", "").trim().ifBlank { "—" }
    val band = station?.band ?: if (song.artist.startsWith("AM")) "AM" else "FM"
    InfoStyleDialog(
        title = "Station info",
        icon = Icons.Rounded.Radio,
        onDismiss = onDismiss
    ) {
        InfoGlassBlock {
            InfoMetaRow("Station", song.title.trim().ifBlank { "Radio" })
            InfoMetaRow("Country", country)
            InfoMetaRow("Band", band)
            InfoMetaRow("Format", song.format.ifBlank { "—" })
            InfoMetaRow("Stream", song.uri.toString(), stacked = true, showDivider = false)
        }
    }
}

private fun shareStation(context: android.content.Context, song: Song) {
    runCatching {
        val name = song.title.trim().ifBlank { "Radio" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, "$name\n${song.uri}")
        }
        context.startActivity(Intent.createChooser(send, "Share station").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun fmtListened(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Live stand-in for the seek bar: bars that travel along and swell with the real output level
 * (same live-level tap the seek bars use). Flat and still while paused.
 */
@Composable
private fun RadioLiveBar(isPlaying: Boolean, accent: Color) {
    val levelProvider = LocalLiveAudioLevel.current
    val transition = rememberInfiniteTransition(label = "radioLiveBar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "radioLiveBarPhase"
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        val count = 48
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (count - 1)) / count
        val level = if (isPlaying) (levelProvider?.invoke() ?: 0.6f).coerceIn(0f, 1f) else 0f
        val p = phase
        for (i in 0 until count) {
            val frac = i / (count - 1).toFloat()
            val h = if (isPlaying) {
                val wave = sin((frac * 2f + p) * 2f * PI.toFloat()) * 0.5f + 0.5f
                size.height * (0.14f + 0.86f * wave * (0.25f + 0.75f * level))
            } else {
                size.height * 0.12f
            }
            drawRoundRect(
                color = lerp(Color.White.copy(alpha = 0.35f), accent, frac),
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

/** The "RADIO" pill that replaces the audio-quality badge on this screen. */
@Composable
private fun RadioBadge(live: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radioBadge")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "radioBadgeDot"
    )
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(RadioAccent.copy(alpha = 0.16f))
            .border(1.dp, RadioAccent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if (live) dotAlpha else 0.4f }
                .background(RadioAccent, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "RADIO",
            color = RadioAccent,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.5.sp
        )
    }
}

/** Two expanding, fading outlines behind the logo — a "broadcasting" cue, only while playing. */
@Composable
private fun RadioPulseRings(active: Boolean, shape: RoundedCornerShape) {
    val transition = rememberInfiniteTransition(label = "radioRings")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "radioRingsPhase"
    )
    if (!active) return
    for (offset in listOf(0f, 0.5f)) {
        val p = (phase + offset) % 1f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f + 0.07f * p
                    scaleX = s
                    scaleY = s
                    alpha = 0.35f * (1f - p)
                }
                .border(2.dp, RadioAccent, shape)
        )
    }
}
