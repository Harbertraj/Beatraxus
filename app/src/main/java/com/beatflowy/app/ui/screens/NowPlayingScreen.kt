package com.beatflowy.app.ui.screens

import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beatflowy.app.R
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.components.WaveformSeekBar
import com.beatflowy.app.ui.components.KaraokeLyricsView
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    song: Song?,
    isPlaying: Boolean,
    progressMs: () -> Long,
    durationMs: Long,
    shuffleMode: Boolean,
    repeatMode: Int,
    uiState: com.beatflowy.app.model.PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleQueue: () -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onPlayFromQueue: (String) -> Unit,
    upcomingSongs: List<Song>,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onToggleLyrics: () -> Unit = {},
    onAdjustOffset: (Long) -> Unit = {}
) {
    if (song == null) return
    val showQueue = uiState.showQueue
    val showLyrics = uiState.showLyrics
    val density = LocalDensity.current
    var showPipelineOverlay by remember(song.id) { mutableStateOf(false) }
    
    val metadataHeight by animateDpAsState(
        targetValue = if (showLyrics) 64.dp else 140.dp,
        animationSpec = tween(600),
        label = "metadataHeight"
    )

    val backgroundBlurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(64f, 64f, Shader.TileMode.DECAL)
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Vibrant Background
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.65f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        renderEffect = backgroundBlurEffect?.asComposeRenderEffect()
                    }
                }
                .then(
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Modifier.blur(40.dp)
                    } else {
                        Modifier
                    }
                ),
            contentScale = ContentScale.Crop,
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )

        val dismissState = remember { mutableStateOf(0f) }
        
        Scaffold(
            modifier = Modifier.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        dismissState.value += dragAmount
                    },
                    onDragEnd = {
                        if (dismissState.value > 100) {
                            onClose()
                        }
                        dismissState.value = 0f
                    }
                )
            },
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = {
                        AnimatedContent(
                            targetState = showLyrics,
                            transitionSpec = {
                                (fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 2 })
                                    .togetherWith(fadeOut(tween(600)) + slideOutVertically(tween(600)) { -it / 2 })
                            },
                            label = "titleTransition"
                        ) { lyricsVisible ->
                            if (lyricsVisible) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        ),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.6f)
                                        ),
                                        maxLines = 1
                                    )
                                }
                            } else {
                                Text(
                                    if (showQueue) "Queue" else "Now Playing",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp,
                                        letterSpacing = 1.sp
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = if (showQueue) onToggleQueue else onClose) {
                            Icon(
                                if (showQueue) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.KeyboardArrowDown,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
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
                if (showQueue) {
                    QueueView(
                        currentSong = song,
                        upcomingSongs = upcomingSongs,
                        onRemoveFromQueue = onRemoveFromQueue,
                        onMove = onMoveInQueue,
                        onPlayFromQueue = {
                            onPlayFromQueue(it)
                            onToggleQueue()
                        },
                        onClose = onToggleQueue
                    )
                } else {
                    // Middle Section: Album Art or Lyrics
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !showLyrics,
                            enter = fadeIn(tween(800)),
                            exit = fadeOut(tween(800))
                        ) {
                            val context = LocalContext.current
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.86f)
                                    .aspectRatio(1f)
                                    .padding(top = 24.dp, bottom = 4.dp)
                                    .graphicsLayer {
                                        scaleX = 1f
                                        scaleY = 1f
                                        translationY = with(density) { 52.dp.toPx() }
                                    }
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .shadow(24.dp, RoundedCornerShape(28.dp)),
                                    shape = RoundedCornerShape(28.dp),
                                    color = Color(0xFF12121A),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        Color.White.copy(alpha = 0.06f),
                                                        Color.Black.copy(alpha = 0.16f)
                                                    )
                                                )
                                            )
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(song.albumArtUri)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = null,
                                            placeholder = painterResource(R.drawable.ic_album_placeholder),
                                            error = painterResource(R.drawable.ic_album_placeholder),
                                            fallback = painterResource(R.drawable.ic_album_placeholder),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.White.copy(alpha = 0.02f),
                                                            Color.Transparent,
                                                            Color.Black.copy(alpha = 0.08f)
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = showLyrics,
                            enter = fadeIn(tween(800)),
                            exit = fadeOut(tween(800))
                        ) {
                            KaraokeLyricsView(
                                lyrics = uiState.lyrics,
                                currentIndex = uiState.lyricsCurrentIndex,
                                currentProgressMs = progressMs(),
                                lyricsOffsetMs = uiState.lyricsOffsetMs,
                                isLoading = uiState.isLoadingLyrics,
                                lyricsSource = uiState.lyricsSource,
                                onLineClick = onSeek,
                                onAdjustOffset = onAdjustOffset,
                                modifier = Modifier.fillMaxSize(),
                                onSwipeDown = { onClose() }
                            )
                        }
                    }

                    // Metadata/Controls Section
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metadataHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            if (!showLyrics) {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            // Audio Quality Badge (Moved between Album Art and Song Name)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !showLyrics,
                                enter = fadeIn(tween(600)) + expandVertically(tween(600)),
                                exit = fadeOut(tween(600)) + shrinkVertically(tween(600))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                ) {
                                    var isVisible by remember(song.id) { mutableStateOf(false) }
                                    LaunchedEffect(song.id) {
                                        delay(500)
                                        isVisible = true
                                    }
                                    
                                    AnimatedContent(
                                        targetState = if (isVisible) song else null,
                                        transitionSpec = {
                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.8f))
                                                .togetherWith(fadeOut(tween(150)))
                                        },
                                        label = "badgeVisibility"
                                    ) { currentSong ->
                                        if (currentSong != null) {
                                            AudioQualityBadge(
                                                song = currentSong,
                                                uiState = uiState,
                                                onClick = onOpenEqualizer,
                                                onLongPress = { showPipelineOverlay = true }
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            // Favorite Icon
                            IconButton(onClick = onFavoriteClick) {
                                Icon(
                                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    null,
                                    tint = if (isFavorite) Color(0xFFFF4081) else Color.White.copy(0.7f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Dynamic Middle Content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = !showLyrics,
                                    enter = fadeIn(tween(600)) + expandHorizontally(tween(600)),
                                    exit = fadeOut(tween(600)) + shrinkHorizontally(tween(600))
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = song.title,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 26.sp
                                            ),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(if (showLyrics) 2.dp else 8.dp))
                                        Text(
                                            text = "${song.artist} • ${song.album}",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 16.sp,
                                                color = Color.White.copy(alpha = 0.6f)
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { 
                                                onNavigateToAlbum(song.album)
                                                onClose()
                                            }
                                        )
                                    }
                                }

                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showLyrics,
                                    enter = fadeIn(tween(600)) + expandHorizontally(tween(600)),
                                    exit = fadeOut(tween(600)) + shrinkHorizontally(tween(600))
                                ) {
                                    val progress = if (durationMs > 0) (progressMs().toFloat() / durationMs) else 0f
                                    var seekTarget by remember { mutableStateOf<Float?>(null) }
                                    
                                    val seekHeight by animateDpAsState(
                                        targetValue = 40.dp,
                                        animationSpec = tween(600),
                                        label = "seekHeight"
                                    )

                                    WaveformSeekBar(
                                        progress = seekTarget ?: progress,
                                        onProgressChange = { 
                                            seekTarget = it
                                            onSeek((it * durationMs).toLong())
                                        },
                                        activeColor = Color.White,
                                        inactiveColor = Color.White.copy(0.2f),
                                        seed = song.id.hashCode(),
                                        progressPollKey = if (seekTarget != null) 0 else (progressMs() / 150),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(seekHeight)
                                    )

                                    LaunchedEffect(progressMs()) {
                                        if (seekTarget != null) {
                                            val currentActual = progressMs().toFloat() / durationMs
                                            if (Math.abs(currentActual - seekTarget!!) < 0.01f) {
                                                seekTarget = null
                                            }
                                        }
                                    }
                                }
                            }

                            // Lyrics Icon
                            IconButton(onClick = onToggleLyrics) {
                                Icon(
                                    Icons.Rounded.Lyrics,
                                    null,
                                    tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White.copy(0.7f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    // Seekbar Area (Timers and Technical Info always visible, Big Wavebar conditional)
                        Column(modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.animation.AnimatedVisibility(
                                visible = !showLyrics,
                                enter = expandVertically(tween(600)) + fadeIn(tween(600)),
                                exit = shrinkVertically(tween(600)) + fadeOut(tween(600))
                            ) {
                                val progress = if (durationMs > 0) (progressMs().toFloat() / durationMs) else 0f
                                var seekTarget by remember { mutableStateOf<Float?>(null) }
                                
                                val seekHeight by animateDpAsState(
                                    targetValue = if (showLyrics) 40.dp else 44.dp,
                                    animationSpec = tween(600),
                                    label = "bigSeekHeight"
                                )

                                Column {
                                    Spacer(Modifier.height(16.dp))
                                    WaveformSeekBar(
                                        progress = seekTarget ?: progress,
                                        onProgressChange = { 
                                            seekTarget = it
                                            onSeek((it * durationMs).toLong())
                                        },
                                        activeColor = Color.White,
                                        inactiveColor = Color.White.copy(0.2f),
                                        seed = song.id.hashCode(),
                                        progressPollKey = if (seekTarget != null) 0 else (progressMs() / 150),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(seekHeight)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    
                                    LaunchedEffect(progressMs()) {
                                        if (seekTarget != null) {
                                            val currentActual = progressMs().toFloat() / durationMs
                                            if (Math.abs(currentActual - seekTarget!!) < 0.01f) {
                                                seekTarget = null
                                            }
                                        }
                                    }
                                }
                            }
                            
                                Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(fmtTime(progressMs()), color = Color.White.copy(0.5f), fontSize = 12.sp, modifier = Modifier.width(45.dp))
                                TechnicalInfo(song, uiState)
                                Text(fmtTime(durationMs), color = Color.White.copy(0.5f), fontSize = 12.sp, modifier = Modifier.width(45.dp), textAlign = TextAlign.End)
                            }
                        }
                    }

                    // Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrevious) {
                            Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(44.dp))
                        }
                        Spacer(Modifier.width(24.dp))
                        Surface(
                            modifier = Modifier
                                .size(72.dp)
                                .clickable { onPlayPause() },
                            shape = CircleShape,
                            color = Color.White
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val currentIsPlaying = remember(isPlaying) { isPlaying }
                                Icon(
                                    if (currentIsPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(onClick = onNext) {
                            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(44.dp))
                        }
                    }

                    // Bottom Control Pill
                    Surface(
                        shape = RoundedCornerShape(40.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 24.dp)
                            .fillMaxWidth(0.9f)
                            .height(64.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(onClick = onShuffle) {
                                    Icon(
                                        if (shuffleMode) Icons.Rounded.Shuffle else Icons.Outlined.Shuffle,
                                        null,
                                        tint = if (shuffleMode) Color(0xFF40C4FF) else Color.White.copy(0.6f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            VerticalDivider(
                                modifier = Modifier.height(24.dp),
                                thickness = 1.dp,
                                color = Color.White.copy(alpha = 0.1f)
                            )
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(onClick = onRepeat) {
                                    val icon = when (repeatMode) {
                                        1 -> Icons.Rounded.RepeatOne
                                        2 -> Icons.Rounded.Repeat
                                        else -> Icons.Outlined.Repeat
                                    }
                                    Icon(
                                        icon,
                                        null,
                                        tint = if (repeatMode != 0) Color(0xFF40C4FF) else Color.White.copy(0.6f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            VerticalDivider(
                                modifier = Modifier.height(24.dp),
                                thickness = 1.dp,
                                color = Color.White.copy(alpha = 0.1f)
                            )
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(onClick = onOpenEqualizer) {
                                    Icon(
                                        Icons.Outlined.Equalizer,
                                        null,
                                        tint = Color.White.copy(0.6f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            VerticalDivider(
                                modifier = Modifier.height(24.dp),
                                thickness = 1.dp,
                                color = Color.White.copy(alpha = 0.1f)
                            )
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(onClick = onToggleQueue) {
                                    Icon(
                                        if (showQueue) Icons.AutoMirrored.Rounded.QueueMusic else Icons.AutoMirrored.Outlined.QueueMusic,
                                        null,
                                        tint = if (showQueue) Color(0xFF40C4FF) else Color.White.copy(0.6f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showPipelineOverlay && !showQueue,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180))
            ) {
                AudioPipelineOverlay(
                    song = song,
                    uiState = uiState,
                    onDismiss = { showPipelineOverlay = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 112.dp)
                )
            }
        }
    }
}

@Composable
fun AudioQualityBadge(
    song: Song,
    uiState: com.beatflowy.app.model.PlayerUiState,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val format = uiState.format.lowercase().ifEmpty { song.format.lowercase() }
    val bitDepth = if (uiState.bitDepth > 0) uiState.bitDepth else song.bitDepth
    val sampleRate = if (uiState.inputSampleRate > 0) uiState.inputSampleRate else song.sampleRateHz
    val bitrate = if (uiState.bitrate > 0) uiState.bitrate else song.bitrate

    // Improved ALAC & AAC logic:
    // M4A files under 10.5MB for a 5min song are typically AAC.
    // Logic: If size < 2.1MB per minute, it's definitely AAC (5min = 10.5MB).
    val durationMin = song.durationMs / 60000.0
    val sizeMb = song.fileSizeBytes / (1024.0 * 1024.0)
    // If the file is M4A and bitrate is less than 450kbps, or density is low, it's AAC.
    val isLikelyLossyM4A = (format == "m4a" || format == "mp4") && 
        ((durationMin > 0 && (sizeMb / durationMin) < 2.1) || (bitrate > 0 && bitrate < 450000))

    val isALAC = format.contains("alac") || (format == "m4a" && !isLikelyLossyM4A)
    val isAAC = format == "aac" || (format == "m4a" && isLikelyLossyM4A) || (format == "mp4")
    
    val isLosslessFormat = format.contains("flac") || isALAC || format.contains("wav")
    val isHiRes = (bitDepth >= 24 || sampleRate > 48000) && isLosslessFormat
    val isLossless = isLosslessFormat && !isHiRes
    
    val badgeColor = when {
        isHiRes -> Color(0xFFFFD700)
        isALAC || isLossless -> Color(0xFF40C4FF)
        else -> Color(0xFFC0C0C0)
    }
    
    val label = when {
        isHiRes -> "HI-RES LOSSLESS"
        isALAC -> "ALAC LOSSLESS"
        isLossless -> "LOSSLESS"
        isAAC -> "AAC QUALITY"
        else -> "LOW QUALITY"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.5f),
        border = BorderStroke(1.5.dp, badgeColor.copy(alpha = 0.8f)),
        modifier = Modifier
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = badgeColor.copy(alpha = glowAlpha),
                spotColor = badgeColor.copy(alpha = glowAlpha)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Rounded.HighQuality, 
                null, 
                tint = badgeColor, 
                modifier = Modifier.size(20.dp).graphicsLayer {
                    shadowElevation = 15f
                    spotShadowColor = badgeColor
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                ),
                color = badgeColor
            )
        }
    }
}

private data class PipelineOverlayState(
    val codec: String,
    val bitrateLabel: String,
    val sampleRateLabel: String,
    val bitDepthLabel: String,
    val outputPath: String,
    val effectsLabel: String
)

@Composable
private fun AudioPipelineOverlay(
    song: Song,
    uiState: com.beatflowy.app.model.PlayerUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestUiState by rememberUpdatedState(uiState)
    val latestSong by rememberUpdatedState(song)
    val overlayState by produceState(
        initialValue = buildPipelineOverlayState(song, uiState),
        key1 = song.id
    ) {
        while (true) {
            value = buildPipelineOverlayState(latestSong, latestUiState)
            delay(500)
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .clickable(onClick = {}),
            shape = RoundedCornerShape(26.dp),
            color = Color(0xEE121218),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 12.dp,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Audio Pipeline",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = Color.White
                )
                PipelineInfoRow("Codec", overlayState.codec)
                PipelineInfoRow("Bitrate", overlayState.bitrateLabel)
                PipelineInfoRow("Sample Rate", overlayState.sampleRateLabel)
                PipelineInfoRow("Bit Depth", overlayState.bitDepthLabel)
                PipelineInfoRow("Output Path", overlayState.outputPath)
                PipelineInfoRow("Effects", overlayState.effectsLabel)
            }
        }
    }
}

@Composable
private fun PipelineInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            ),
            color = Color.White.copy(alpha = 0.62f)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = Color.White,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun TechnicalInfo(song: Song, uiState: com.beatflowy.app.model.PlayerUiState) {
    val format = uiState.format.lowercase().ifEmpty { song.format.lowercase() }
    
    val durationMin = song.durationMs / 60000.0
    val sizeMb = song.fileSizeBytes / (1024.0 * 1024.0)
    val isLikelyLossyM4A = (format == "m4a" || format == "mp4") && 
        ((durationMin > 0 && (sizeMb / durationMin) < 2.1) || (uiState.bitrate > 0 && uiState.bitrate < 450000))

    val isALAC = format.contains("alac") || (format == "m4a" && !isLikelyLossyM4A)
    val isHiRes = (uiState.bitDepth >= 24 || uiState.inputSampleRate > 48000) && (format.contains("flac") || isALAC || format.contains("wav"))
    
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.08f)) {
        val info = buildString {
            // Priority display for bit depth: Force 24BIT only if it's actually lossless Hi-Res
            if (isHiRes) {
                append("24BIT ")
            } else if (uiState.bitDepth > 0) {
                append("${uiState.bitDepth}BIT ")
            } else if (song.bitDepth > 0) {
                append("${song.bitDepth}BIT ")
            }
            
            val sampleRate = if (uiState.inputSampleRate > 0) uiState.inputSampleRate else song.sampleRateHz
            append("${String.format(Locale.US, "%.1f", sampleRate / 1000.0)}KHZ ")
            
            // Fixed bitrate display: fallback to calculating from size if metadata is 0
            val currentBitrate = if (uiState.bitrate > 0) uiState.bitrate else song.bitrate
            val displayBitrate: Long = if (currentBitrate > 0) {
                currentBitrate.toLong() / 1000
            } else if (song.durationMs > 0) {
                (song.fileSizeBytes * 8) / song.durationMs
            } else 0L
            
            if (displayBitrate > 0) {
                append("${displayBitrate}KBPS ")
            }

            val displayFormat = when {
                isALAC -> "ALAC"
                format == "m4a" || format == "aac" -> "AAC"
                else -> format.uppercase()
            }
            append(displayFormat)
        }
        Text(
            text = info,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp, 
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun QueueView(
    currentSong: Song,
    upcomingSongs: List<Song>,
    onRemoveFromQueue: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPlayFromQueue: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { onClose() },
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.1f)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = currentSong.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                    Text(currentSong.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
                    Text(currentSong.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                }
            }
        }

        Text("UP NEXT", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.padding(vertical = 8.dp))

        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to -> onMove(from.index, to.index) }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(upcomingSongs, key = { it.id }) { song ->
                ReorderableItem(reorderableState, key = song.id) { isDragging ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onPlayFromQueue(song.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDragging) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DragHandle, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.draggableHandle())
                            Spacer(Modifier.width(12.dp))
                            AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                            }
                            IconButton(onClick = { onRemoveFromQueue(song.id) }) {
                                Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}

private fun buildPipelineOverlayState(
    song: Song,
    uiState: com.beatflowy.app.model.PlayerUiState
): PipelineOverlayState {
    val codec = uiState.format.ifBlank { song.format }.ifBlank { "Unknown" }.uppercase(Locale.US)
    val bitrate = if (uiState.bitrate > 0) uiState.bitrate else song.bitrate
    val inputSampleRate = if (uiState.inputSampleRate > 0) uiState.inputSampleRate else song.sampleRateHz
    val outputSampleRate = if (uiState.outputSampleRate > 0) uiState.outputSampleRate else inputSampleRate
    val bitDepth = if (uiState.bitDepth > 0) uiState.bitDepth else song.bitDepth
    val effects = buildList {
        addAll(uiState.pipelineActiveEffects)
        add("DVC ${if (uiState.pipelineDvcEnabled) "On" else "Off"}")
        add("Resampler ${if (uiState.pipelineResamplerEnabled) "On" else "Bypass"}")
        uiState.autoEqProfileName?.let { add("Profile $it") }
    }.ifEmpty { listOf("None") }.joinToString(" | ")

    return PipelineOverlayState(
        codec = codec,
        bitrateLabel = formatPipelineBitrate(bitrate),
        sampleRateLabel = "${formatPipelineSampleRate(inputSampleRate)} -> ${formatPipelineSampleRate(outputSampleRate)}",
        bitDepthLabel = if (bitDepth > 0) "$bitDepth-bit" else "Unknown",
        outputPath = uiState.pipelineOutputPath,
        effectsLabel = effects
    )
}

private fun formatPipelineBitrate(bitrate: Int): String {
    if (bitrate <= 0) return "Unknown"
    return "${bitrate / 1000} kbps"
}

private fun formatPipelineSampleRate(sampleRate: Int): String {
    if (sampleRate <= 0) return "Unknown"
    return String.format(Locale.US, "%.1f kHz", sampleRate / 1000f)
}
