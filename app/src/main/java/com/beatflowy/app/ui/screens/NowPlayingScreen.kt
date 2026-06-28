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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.ImageLoader
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.palette.graphics.Palette
import com.beatflowy.app.R
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.ui.components.WaveformSeekBar
import com.beatflowy.app.ui.components.KaraokeLyricsView
import com.beatflowy.app.ui.components.PremiumSwitch
import com.beatflowy.app.ui.components.SongInfoDialog
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    previousSongs: List<Song>,
    upcomingSongs: List<Song>,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onToggleLyrics: () -> Unit = {},
    onAdjustOffset: (Long) -> Unit = {},
    onSetLyricsOffset: (Long) -> Unit = {},
    showPipelineOverlay: Boolean = false,
    onTogglePipeline: (Boolean) -> Unit = {},
    onSetSleepTimer: (Int, Boolean, Int) -> Unit = { _, _, _ -> },
    onStopSleepTimer: () -> Unit = {}
) {
    if (song == null) return
    val showQueue = uiState.showQueue
    val showLyrics = uiState.showLyrics
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showSongInfo by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dominantColorsCache = remember { mutableStateMapOf<String, Color>() }
    val currentDominantColor = dominantColorsCache[song.id] ?: Color(0xFF2C2C2C)
    
    LaunchedEffect(song.id, song.albumArtUri) {
        if (dominantColorsCache.containsKey(song.id)) return@LaunchedEffect
        
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(song.albumArtUri)
            .allowHardware(false)
            .size(100, 100)
            .build()
            
        val result = (loader.execute(request) as? SuccessResult)?.drawable
        val bitmap = (result as? BitmapDrawable)?.bitmap
        if (bitmap != null) {
            Palette.from(bitmap).generate { palette ->
                val color = palette?.vibrantSwatch?.rgb
                    ?: palette?.dominantSwatch?.rgb
                if (color != null) {
                    dominantColorsCache[song.id] = Color(color)
                }
            }
        }
    }

    // Key ensures derived state (progress calc) resets on song change
    val songChangeKey = song.id to (durationMs)

    var badgeVisible by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id) {
        delay(200)
        badgeVisible = true
    }
    
    val metadataHeight by animateDpAsState(
        targetValue = if (showLyrics) 64.dp else 90.dp,
        animationSpec = tween(600),
        label = "metadataHeight"
    )

    val backgroundBlurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(250f, 250f, Shader.TileMode.DECAL)
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Vibrant Background
        AnimatedContent(
            targetState = song.albumArtUri,
            transitionSpec = {
                fadeIn(tween(700)) togetherWith fadeOut(tween(700))
            },
            label = "vibrantBackground"
        ) { artUri ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artUri)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.85f
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            renderEffect = backgroundBlurEffect?.asComposeRenderEffect()
                        }
                    }
                    .then(
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            Modifier.blur(150.dp)
                        } else {
                            Modifier
                        }
                    ),
                contentScale = ContentScale.Crop,
            )
        }
        
        Box(
            modifier = Modifier
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

        val dismissState = remember { mutableStateOf(0f) }
        
        Scaffold(
            modifier = Modifier.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        dismissState.value += dragAmount
                    },
                    onDragEnd = {
                        if (dismissState.value > 100) {
                            onTogglePipeline(false)
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
                            modifier = Modifier.offset(y = (-4).dp),
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
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 22.sp,
                                        letterSpacing = 1.sp
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showQueue) onToggleQueue()
                            else {
                                onTogglePipeline(false)
                                onClose()
                            }
                        }) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    },
                    actions = {
                        AnimatedContent(
                            targetState = showQueue,
                            transitionSpec = {
                                (fadeIn(tween(700)) + scaleIn(initialScale = 0.85f))
                                    .togetherWith(fadeOut(tween(700)) + scaleOut(targetScale = 0.85f))
                            },
                            label = "actionToggle",
                            modifier = Modifier.padding(end = 12.dp)
                        ) { queueVisible ->
                            if (!queueVisible) {
                                IconButton(onClick = { showSongInfo = true }) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        null,
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            } else {
                                // Sleep Timer Button
                                Surface(
                                    onClick = { showSleepTimerSheet = true },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (uiState.isSleepTimerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, if (uiState.isSleepTimerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = if (uiState.isSleepTimerActive) 10.dp else 8.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Timer,
                                            null,
                                            tint = if (uiState.isSleepTimerActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        if (uiState.isSleepTimerActive) {
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
                if (showQueue) {
                    QueueView(
                        currentSong = song,
                        previousSongs = previousSongs,
                        upcomingSongs = upcomingSongs,
                        onRemoveFromQueue = onRemoveFromQueue,
                        onMove = onMoveInQueue,
                        onPlayFromQueue = {
                            onPlayFromQueue(it)
                        },
                        onClose = onToggleQueue,
                        dominantColor = currentDominantColor
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    // Middle Section: Album Art or Lyrics
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val albumArtAlpha by animateFloatAsState(
                            targetValue = if (showLyrics) 0f else 1f,
                            animationSpec = tween(600),
                            label = "albumArtAlpha"
                        )
                        val albumArtScale by animateFloatAsState(
                            targetValue = if (showLyrics) 0.85f else 1f,
                            animationSpec = tween(600),
                            label = "albumArtScale"
                        )
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.93f)
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    alpha = albumArtAlpha
                                    scaleX = albumArtScale
                                    scaleY = albumArtScale
                                    // Use pointerInteropFilter or similar if we want to disable clicks when hidden
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
                                    AnimatedContent(
                                        targetState = song.albumArtUri,
                                        transitionSpec = {
                                            fadeIn(tween(500)) togetherWith fadeOut(tween(500))
                                        },
                                        label = "mainAlbumArt"
                                    ) { artUri ->
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(artUri)
                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = null,
                                            error = painterResource(R.drawable.ic_album_default),
                                            fallback = painterResource(R.drawable.ic_album_default),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
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

                        androidx.compose.animation.AnimatedVisibility(
                            visible = showLyrics,
                            enter = fadeIn(tween(500)) + scaleIn(initialScale = 1.05f, animationSpec = tween(500)),
                            exit = fadeOut(tween(400)) + scaleOut(targetScale = 1.05f, animationSpec = tween(400))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
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
                                    onSetOffset = onSetLyricsOffset,
                                    modifier = Modifier.fillMaxSize(),
                                    onSwipeDown = { onClose() }
                                )
                            }
                        }
                    }

                    // Metadata/Controls Section
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metadataHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !showLyrics,
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier.height(36.dp),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = badgeVisible,
                                            enter = fadeIn(tween(600)) + scaleIn(initialScale = 0.8f, animationSpec = tween(600)),
                                            exit = fadeOut(tween(0))
                                        ) {
                                            AudioQualityBadge(
                                                song = song,
                                                uiState = uiState,
                                                onClick = { },
                                                onLongPress = { onTogglePipeline(true) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(0.dp))
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
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = !showLyrics,
                                    enter = fadeIn(tween(600)) + expandHorizontally(tween(600)),
                                    exit = fadeOut(tween(600)) + shrinkHorizontally(tween(600))
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = song.title,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 26.sp,
                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                            ),
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val titleSpacerHeight by animateDpAsState(
                                            targetValue = 0.dp,
                                            animationSpec = tween(600),
                                            label = "titleSpacerHeight"
                                        )
                                        Spacer(modifier = Modifier.height(titleSpacerHeight))
                                        Text(
                                            text = "${song.artist} • ${song.album}",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 16.sp,
                                                color = Color.White.copy(alpha = 0.6f),
                                                lineHeight = 24.sp,
                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                            ),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .offset(y = (3).dp)
                                                .basicMarquee(iterations = Int.MAX_VALUE)
                                                .clickable { 
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
                                    val progress = if (durationMs > 0) {
                                        (progressMs().toFloat() / durationMs).coerceIn(0f, 1f)
                                    } else 0f
                                    
                                    val seekHeight by animateDpAsState(
                                        targetValue = 40.dp,
                                        animationSpec = tween(600),
                                        label = "seekHeight"
                                    )

                                    WaveformSeekBar(
                                        progress = progress,
                                        onProgressChange = { },
                                        onProgressFinished = {
                                            onSeek((it * durationMs).toLong())
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(seekHeight),
                                        activeColor = Color.White,
                                        inactiveColor = Color.White.copy(0.2f),
                                        seed = song.id.hashCode()
                                    )
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
                                val progress = if (durationMs > 0) {
                                    (progressMs().toFloat() / durationMs).coerceIn(0f, 1f)
                                } else 0f
                                
                                val seekHeight by animateDpAsState(
                                    targetValue = if (showLyrics) 40.dp else 44.dp,
                                    animationSpec = tween(600),
                                    label = "bigSeekHeight"
                                )

                                Column {
                                    Spacer(Modifier.height(18.dp))
                                    WaveformSeekBar(
                                        progress = progress,
                                        onProgressChange = { },
                                        onProgressFinished = {
                                            onSeek((it * durationMs).toLong())
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(seekHeight),
                                        activeColor = Color.White,
                                        inactiveColor = Color.White.copy(0.2f),
                                        seed = song.id.hashCode()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            
                                Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
                            onClick = onPlayPause,
                            modifier = Modifier
                                .size(78.dp)
                                .shadow(16.dp, CircleShape, ambientColor = Color.White.copy(0.2f)),
                            shape = CircleShape,
                            color = Color.White,
                            border = BorderStroke(4.dp, Brush.verticalGradient(listOf(Color.White, Color.White.copy(0.8f))))
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.background(
                                    Brush.radialGradient(
                                        colors = listOf(Color.White, Color(0xFFE0E0E0)),
                                        radius = 120f
                                    )
                                )
                            ) {
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

                    // Redesigned Bottom Control Pills - Claymorphism with Transparency
                    Row(
                        modifier = Modifier
                            .padding(top = 16.dp, bottom = 32.dp)
                            .fillMaxWidth(0.95f)
                            .height(64.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val pillModifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .shadow(
                                elevation = 12.dp,
                                shape = RoundedCornerShape(32.dp),
                                ambientColor = Color.Black.copy(alpha = 0.4f),
                                spotColor = Color.Black.copy(alpha = 0.6f)
                            )
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                                ),
                                shape = RoundedCornerShape(32.dp)
                            )
                            .drawBehind {
                                // Top-left inner highlight (Clay volume effect)
                                drawRoundRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
                                        start = Offset(0f, 0f),
                                        end = Offset(size.width * 0.45f, size.height * 0.45f)
                                    ),
                                    cornerRadius = CornerRadius(32.dp.toPx())
                                )
                                // Bottom-right inner shadow (Clay depth effect)
                                drawRoundRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f)),
                                        start = Offset(size.width * 0.55f, size.height * 0.55f),
                                        end = Offset(size.width, size.height)
                                    ),
                                    cornerRadius = CornerRadius(32.dp.toPx())
                                )
                            }

                        // Pill 1: Shuffle and Repeat
                        Box(modifier = pillModifier, contentAlignment = Alignment.Center) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = onShuffle) {
                                        Icon(
                                            if (shuffleMode) Icons.Rounded.Shuffle else Icons.Outlined.Shuffle,
                                            null,
                                            tint = if (shuffleMode) Color.White else Color.White.copy(0.45f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                VerticalDivider(
                                    modifier = Modifier.height(20.dp),
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
                                            tint = if (repeatMode != 0) Color.White else Color.White.copy(0.45f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Pill 2: Queue and Equalizer
                        Box(modifier = pillModifier, contentAlignment = Alignment.Center) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = onToggleQueue) {
                                        Icon(
                                            if (showQueue) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.AutoMirrored.Outlined.PlaylistPlay,
                                            null,
                                            tint = if (showQueue) Color.White else Color.White.copy(0.45f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                VerticalDivider(
                                    modifier = Modifier.height(20.dp),
                                    thickness = 1.dp,
                                    color = Color.White.copy(alpha = 0.1f)
                                )
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = onOpenEqualizer) {
                                        val eqEnabled = uiState.dsp.config.eqEnabled
                                        Icon(
                                            if (eqEnabled) Icons.Rounded.Equalizer else Icons.Outlined.Equalizer,
                                            null,
                                            tint = if (eqEnabled) Color.White else Color.White.copy(0.45f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }


                }
            }
        }

        if (showSleepTimerSheet) {
            SleepTimerSheet(
                albumArtUri = song.albumArtUri,
                uiState = uiState,
                onSetTimer = { seconds, finishTrack, playCount ->
                    onSetSleepTimer(seconds, finishTrack, playCount)
                },
                onStopTimer = onStopSleepTimer,
                onDismiss = { showSleepTimerSheet = false }
            )
        }

        if (showSongInfo) {
            SongInfoDialog(
                song = song,
                onDismiss = { showSongInfo = false }
            )
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
    val format = song.format.lowercase()
    val bitDepth = song.bitDepth
    val sampleRate = song.sampleRateHz
    val bitrate = song.bitrate

    val durationMin = song.durationMs / 60000.0
    val sizeMb = song.fileSizeBytes / (1024.0 * 1024.0)
    val isLikelyLossyM4A = (format == "m4a" || format == "mp4" || format == "aac") && 
        ((durationMin > 0 && (sizeMb / durationMin) < 2.3) || (bitrate > 0 && bitrate < 400000))

    val isALAC = format.contains("alac") || ((format == "m4a" || format == "mp4") && !isLikelyLossyM4A)
    val isLosslessFormat = format.contains("flac") || isALAC || format.contains("wav") || format.contains("dsd") || format.contains("aiff")
    val isHiRes = (bitDepth >= 24 || sampleRate > 48000) && isLosslessFormat
    
    val primaryColor = when {
        isHiRes -> Color(0xFFFFD54F)       // Premium Gold
        isLosslessFormat -> Color(0xFF4FC3F7) // Premium Cyan
        else -> Color(0xFFE0E0E0)          // Silver
    }

    val secondaryColor = when {
        isHiRes -> Color(0xFFFF8F00)
        isLosslessFormat -> Color(0xFF00ACC1)
        else -> Color(0xFF757575)
    }

    val label = when {
        isHiRes -> "HIRES LOSSLESS"
        isLosslessFormat -> "LOSSLESS"
        else -> "${format.uppercase(Locale.US)} QUALITY"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "premiumBadge")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Surface(
        modifier = Modifier
            .graphicsLayer {
                shadowElevation = 28f
                shape = RoundedCornerShape(18.dp)
                clip = false
            }
            .shadow(
                elevation = 26.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = primaryColor.copy(alpha = glowAlpha * 0.55f),
                spotColor = primaryColor.copy(alpha = glowAlpha * 0.75f)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xCC0A0A0F),
        border = BorderStroke(
            1.4.dp,
            Brush.linearGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.95f),
                    secondaryColor.copy(alpha = 0.75f),
                    Color.White.copy(alpha = 0.35f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                            primaryColor.copy(alpha = 0.08f)
                        )
                    )
                )
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            start = Offset(shimmerTranslate - 180f, 0f),
                            end = Offset(shimmerTranslate, size.height)
                        )
                    )
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (isHiRes) Icons.Rounded.Diamond else Icons.Rounded.HighQuality,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            shadowElevation = 18f
                            spotShadowColor = primaryColor
                        }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    ),
                    color = primaryColor
                )
            }
        }
    }
}

data class PipelineOverlayState(
    val codec: String,
    val bitrateLabel: String,
    val sampleRateLabel: String,
    val bitDepthLabel: String,
    val outputPath: String,
    val summary: String,
    val effectsLabel: String
)

@Composable
fun AudioPipelineOverlay(
    song: Song,
    uiState: com.beatflowy.app.model.PlayerUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overlayState = remember(song, uiState) {
        buildPipelineOverlayState(song, uiState)
    }

    Box(
        modifier = modifier
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 112.dp)
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
                    text = "AUDIO PIPELINE",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp
                    ),
                    color = Color.White
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.White.copy(0.05f))
                PipelineInfoRow("Codec", overlayState.codec)
                PipelineInfoRow("Bitrate", overlayState.bitrateLabel)
                PipelineInfoRow("Sample Rate", overlayState.sampleRateLabel)
                PipelineInfoRow("Bit Depth", overlayState.bitDepthLabel)
                PipelineInfoRow("Output Path", overlayState.outputPath)
                PipelineInfoRow("Pipeline", overlayState.summary)
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
    val rawFormat = song.format.lowercase()
    val bitDepth = when {
        rawFormat.contains("24") -> 24
        rawFormat.contains("16") -> 16
        song.bitDepth > 0 -> song.bitDepth
        else -> 16
    }
    val sampleRate = song.sampleRateHz
    val bitrate = song.bitrate
    
    val khz = String.format(Locale.US, "%.1f", sampleRate / 1000.0).removeSuffix(".0")
    
    val accurateBitrate = if (bitrate > 0) {
        bitrate.toLong()
    } else if (song.durationMs > 0) {
        (song.fileSizeBytes * 8 * 1000) / song.durationMs
    } else 0L
    val kbps = accurateBitrate / 1000

    Surface(
        shape = RoundedCornerShape(16.dp), 
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.2f), Color.Transparent)))
    ) {
        val info = buildString {
            if (song.source == SongSource.GDRIVE) {
                // 16BIT | 96KHZ | FLAC | GDRIVE
                append("${bitDepth}BIT | ")
                append("${khz}KHZ | ")
                append("${rawFormat.uppercase(Locale.US)} | ")
                append("GDRIVE")
            } else {
                // 16BIT | 1566KBPS | 96KHZ | ALAC
                append("${bitDepth}BIT | ")
                if (kbps > 0) {
                    append("${kbps}KBPS | ")
                }
                append("${khz}KHZ")

                val durationMin = song.durationMs / 60000.0
                val sizeMb = song.fileSizeBytes / (1024.0 * 1024.0)
                val isLikelyLossyM4A = (rawFormat == "m4a" || rawFormat == "mp4" || rawFormat == "aac") && 
                    ((durationMin > 0 && (sizeMb / durationMin) < 2.3) || (bitrate > 0 && bitrate < 400000))
                
                val displayFormat = when {
                    rawFormat.contains("alac") || ((rawFormat == "m4a" || rawFormat == "mp4") && !isLikelyLossyM4A) -> "ALAC"
                    else -> rawFormat.uppercase(Locale.US)
                }
                append(" | $displayFormat")
            }
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
    previousSongs: List<Song>,
    upcomingSongs: List<Song>,
    onRemoveFromQueue: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPlayFromQueue: (String) -> Unit,
    onClose: () -> Unit,
    dominantColor: Color
) {
    val context = LocalContext.current
    
    val animatedDominantColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(500),
        label = "dominantColor"
    )
    
    val isLight = remember(animatedDominantColor) {
        val luminance = 0.299 * animatedDominantColor.red + 0.587 * animatedDominantColor.green + 0.114 * animatedDominantColor.blue
        luminance > 0.5
    }
    val textColor = if (isLight) Color.Black else Color.White
    val subTextColor = if (isLight) Color.Black.copy(0.7f) else Color.White.copy(0.7f)

    Column(modifier = Modifier.fillMaxSize()) {
        val lazyListState = rememberLazyListState()
        
        val nextUpHeaderIndex = (if (previousSongs.isNotEmpty()) previousSongs.size + 1 else 0) + 1 // +1 for Now Playing
        val firstNextUpIndex = nextUpHeaderIndex + 1

        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            if (from.index >= firstNextUpIndex && to.index >= firstNextUpIndex) {
                val currentPlaylistIndex = previousSongs.size
                val fromPlaylistIndex = currentPlaylistIndex + 1 + (from.index - firstNextUpIndex)
                val toPlaylistIndex = currentPlaylistIndex + 1 + (to.index - firstNextUpIndex)
                onMove(fromPlaylistIndex, toPlaylistIndex)
            }
        }

        // We use a single LazyColumn for the whole queue
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Previous Songs Section
            if (previousSongs.isNotEmpty()) {
                item {
                    SectionHeader("PREVIOUSLY PLAYED", previousSongs.size, animatedDominantColor)
                }
                items(previousSongs, key = { "prev_${it.id}" }) { song ->
                    SongQueueItem(
                        song = song,
                        isCurrent = false,
                        isPrevious = true,
                        animatedDominantColor = animatedDominantColor,
                        onPlay = { onPlayFromQueue(song.id) },
                        onRemove = { onRemoveFromQueue(song.id) }
                    )
                }
            }

            // Enhanced Now Playing Card in Queue
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .shadow(12.dp, RoundedCornerShape(24.dp))
                        .clickable { onClose() },
                    shape = RoundedCornerShape(24.dp),
                    color = animatedDominantColor.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (isLight) 0.1f else 0.2f))
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Subtle gradient overlay for depth
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                                    )
                                )
                        )
                        
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .shadow(8.dp, RoundedCornerShape(14.dp))
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentSong.albumArtUri)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        null,
                                        tint = textColor.copy(alpha = 0.8f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "NOW PLAYING",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp
                                        ),
                                        color = subTextColor
                                    )
                                }
                                Text(
                                    currentSong.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    currentSong.artist,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = subTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                null,
                                tint = textColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Next Up Section
            if (upcomingSongs.isNotEmpty()) {
                item {
                    SectionHeader("NEXT UP", upcomingSongs.size, animatedDominantColor)
                }
                items(upcomingSongs, key = { it.id }) { song ->
                    ReorderableItem(reorderableState, key = song.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 12.dp else 0.dp)
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation, RoundedCornerShape(18.dp))
                                .clickable { onPlayFromQueue(song.id) },
                            shape = RoundedCornerShape(18.dp),
                            color = if (isDragging) 
                                animatedDominantColor.copy(alpha = 0.4f) 
                            else 
                                Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(
                                1.dp, 
                                if (isDragging) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.03f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    null,
                                    tint = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.draggableHandle()
                                )
                                Spacer(Modifier.width(12.dp))
                                Box(modifier = Modifier.size(52.dp)) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(song.albumArtUri)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = Color.White.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveFromQueue(song.id) },
                                    modifier = Modifier.alpha(0.6f)
                                ) {
                                    Icon(Icons.Rounded.RemoveCircleOutline, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            } else if (previousSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                null,
                                tint = Color.White.copy(alpha = 0.1f),
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Queue is empty",
                                color = Color.White.copy(alpha = 0.3f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (title.contains("NEXT")) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.Rounded.History,
                    null,
                    tint = Color.White.copy(0.9f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White
                )
            }
        }
        
        Text(
            "$count SONGS",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}

@Composable
private fun SongQueueItem(
    song: Song,
    isCurrent: Boolean,
    isPrevious: Boolean,
    animatedDominantColor: Color,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() },
        shape = RoundedCornerShape(18.dp),
        color = if (isPrevious) Color.White.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(
            1.dp, 
            if (isPrevious) Color.White.copy(alpha = 0.01f) else Color.White.copy(alpha = 0.03f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).alpha(if (isPrevious) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(52.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.albumArtUri)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.alpha(0.6f)
            ) {
                Icon(Icons.Rounded.RemoveCircleOutline, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}

private fun fmtSleepTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "%d:%02d".format(m, s) else "%ds".format(s)
}

fun buildPipelineOverlayState(
    song: Song,
    uiState: com.beatflowy.app.model.PlayerUiState
): PipelineOverlayState {
    val codec = uiState.format.ifBlank { song.format }.ifBlank { "Unknown" }.uppercase(Locale.US)
    val bitrate = if (uiState.bitrate > 0) uiState.bitrate else song.bitrate
    val inputSampleRate = if (uiState.inputSampleRate > 0) uiState.inputSampleRate else song.sampleRateHz
    val outputSampleRate = if (uiState.outputSampleRate > 0) uiState.outputSampleRate else inputSampleRate
    
    val isMtkHiFi = uiState.outputMode == "HI_RES"
    val outputPathLabel = if (isMtkHiFi) "MTK HI-FI" else "AAudio"

    val effects = buildList {
        addAll(uiState.pipelineActiveEffects.filter { !it.contains("MTK", ignoreCase = true) })
        add("DVC ${if (uiState.pipelineDvcEnabled) "On" else "Off"}")
        add(if (uiState.pipelineResamplerEnabled) uiState.pipelineResamplerType else "Bypass")
        uiState.autoEqProfileName?.let { add("Profile $it") }
    }.filter { it.isNotBlank() }.ifEmpty { listOf("None") }.joinToString(" | ")

    val bitDepthLabel = buildString {
        if (song.bitDepth > 0) append("${song.bitDepth}-bit")
        else if (uiState.bitDepth > 0) append("${uiState.bitDepth}-bit")
        
        if (uiState.outputBitDepth > 0 && uiState.outputBitDepth != song.bitDepth && uiState.outputBitDepth != uiState.bitDepth) {
            append(" -> ${uiState.outputBitDepth}-bit")
        }
    }.ifBlank { "Unknown" }

    val pipelineSummary = uiState.pipelineSummary.ifBlank { 
        "$codec -> PCM -> DSP -> ${if (uiState.pipelineResamplerEnabled) uiState.pipelineResamplerType else "Bypass"} -> $outputPathLabel" 
    }

    return PipelineOverlayState(
        codec = codec,
        bitrateLabel = formatPipelineBitrate(bitrate),
        sampleRateLabel = "${formatPipelineSampleRate(inputSampleRate)} -> ${formatPipelineSampleRate(outputSampleRate)}",
        bitDepthLabel = bitDepthLabel,
        outputPath = outputPathLabel,
        summary = pipelineSummary,
        effectsLabel = effects
    )
}

private fun formatPipelineBitrate(bitrate: Int): String {
    if (bitrate <= 0) return "Unknown"
    return "${bitrate / 1000} kbps"
}

private fun formatPipelineSampleRate(sampleRate: Int): String {
    if (sampleRate <= 0) return "Unknown"
    return if (sampleRate % 1000 == 0) {
        "${sampleRate / 1000} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", sampleRate / 1000f)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    albumArtUri: Uri?,
    uiState: com.beatflowy.app.model.PlayerUiState,
    onSetTimer: (Int, Boolean, Int) -> Unit,
    onStopTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(0, 15, 30, 45, 60, 120, 180, 240) // in minutes
    var timerValue by remember { mutableFloatStateOf(0f) }
    var playCountValue by remember { mutableFloatStateOf(0f) }
    var endOfTrack by remember { mutableStateOf(uiState.sleepTimerFinishTrack) }
    var showCustomPicker by remember { mutableStateOf(false) }

    val selectedMinutes = remember(timerValue) {
        val index = (timerValue * (presets.size - 1)).roundToInt()
        presets[index]
    }

    val selectedPlayCount = remember(playCountValue) {
        (playCountValue * 50).roundToInt()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.8f),
        dragHandle = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp))
                .background(Color(0xFF0F0E0E))
                .windowInsetsPadding(WindowInsets(0.dp))
        ) {
            // Background Layer with enhanced blur and subtle animation
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(albumArtUri)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(160.dp)
                    .alpha(0.3f)
                    .graphicsLayer {
                        scaleX = 1.2f
                        scaleY = 1.2f
                    },
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 48.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // Refined Drag handle
                Box(
                    modifier = Modifier
                        .size(40.dp, 4.dp)
                        .background(Color.White.copy(0.2f), CircleShape)
                )

                // Title Section with active state
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Sleep Timer",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    if (uiState.isSleepTimerActive) {
                        Surface(
                            color = Color(0xFFFFB2AD).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFB2AD).copy(alpha = 0.3f))
                        ) {
                            val remaining = uiState.sleepTimerRemainingSeconds
                            val h = remaining / 3600
                            val m = (remaining % 3600) / 60
                            val s = remaining % 60
                            val countdownText = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
                            
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFFFFB2AD), CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Ends in $countdownText",
                                    color = Color(0xFFFFB2AD),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }
                        }
                    } else {
                        Text(
                            "Set a timer to automatically stop playback",
                            color = Color.White.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Enhanced Control Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(0.03f), RoundedCornerShape(32.dp))
                        .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(32.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    // Timer Slider
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Timer, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Duration", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                text = if (selectedMinutes == 0) "Off" else if (selectedMinutes >= 60) "${selectedMinutes/60}h ${if (selectedMinutes%60>0) "${selectedMinutes%60}m" else ""}" else "${selectedMinutes}m",
                                color = Color(0xFFFFB2AD),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        PremiumSnappingSlider(
                            value = timerValue,
                            onValueChange = { timerValue = it },
                            steps = presets.size
                        )
                    }

                    // Play Count Slider
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Play Count", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                text = if (selectedPlayCount == 0) "Off" else "$selectedPlayCount songs",
                                color = Color(0xFFFFB2AD),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        PremiumSnappingSlider(
                            value = playCountValue,
                            onValueChange = { playCountValue = it },
                            steps = 51
                        )
                    }
                }

                // Switch and Buttons Section
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // End of track switch
                    Surface(
                        onClick = { endOfTrack = !endOfTrack },
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White.copy(0.05f),
                        border = BorderStroke(1.dp, Color.White.copy(0.05f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Finish current track",
                                    color = Color.White.copy(0.9f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Stop only after song finishes",
                                    color = Color.White.copy(0.4f),
                                    fontSize = 12.sp
                                )
                            }
                            PremiumSwitch(
                                checked = endOfTrack,
                                onCheckedChange = { endOfTrack = it }
                            )
                        }
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.isSleepTimerActive) {
                            Button(
                                onClick = {
                                    onStopTimer()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f).height(64.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
                                border = BorderStroke(1.dp, Color.White.copy(0.1f))
                            ) {
                                Text("Stop Timer", color = Color.White.copy(0.9f), fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                if (selectedMinutes > 0 || selectedPlayCount > 0) {
                                    onSetTimer(selectedMinutes * 60, endOfTrack, selectedPlayCount)
                                    onDismiss()
                                } else {
                                    showCustomPicker = true
                                }
                            },
                            modifier = Modifier.weight(if (uiState.isSleepTimerActive) 1.5f else 1f).height(64.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB2AD)),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                        ) {
                            Text(
                                if (selectedMinutes == 0 && selectedPlayCount == 0) "Custom Time" else "Set Timer",
                                color = Color(0xFF2C1817),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                        }
                        
                        if (!uiState.isSleepTimerActive) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color.White.copy(0.05f), RoundedCornerShape(20.dp))
                                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp))
                            ) {
                                Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.6f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomPicker) {
        CustomTimePickerDialog(
            onDismiss = { showCustomPicker = false },
            onConfirm = { seconds ->
                onSetTimer(seconds, endOfTrack, 0)
                showCustomPicker = false
                onDismiss()
            }
        )
    }
}

@Composable
fun PremiumSnappingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    steps: Int
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { 30.dp.toPx() }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(0.05f))
            .pointerInput(steps) {
                detectTapGestures { offset ->
                    val availableWidth = size.width - (horizontalPaddingPx * 2)
                    val raw = ((offset.x - horizontalPaddingPx) / availableWidth).coerceIn(0f, 1f)
                    val stepped = if (steps > 1) {
                        (raw * (steps - 1)).roundToInt() / (steps - 1).toFloat()
                    } else raw
                    
                    if (stepped != value) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onValueChange(stepped)
                    }
                }
            }
            .pointerInput(steps) {
                detectDragGestures { change, _ ->
                    val availableWidth = size.width - (horizontalPaddingPx * 2)
                    val raw = ((change.position.x - horizontalPaddingPx) / availableWidth).coerceIn(0f, 1f)
                    val stepped = if (steps > 1) {
                        (raw * (steps - 1)).roundToInt() / (steps - 1).toFloat()
                    } else raw

                    if (stepped != value) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onValueChange(stepped)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val animatedValue by animateFloatAsState(
            targetValue = value,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "sliderProgress"
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val availableWidth = size.width - (horizontalPaddingPx * 2)
            val thumbX = horizontalPaddingPx + (animatedValue * availableWidth)

            // Track Progress Fill
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF4A2F2D), Color(0xFF6D4542))
                ),
                size = Size(thumbX, size.height)
            )
            
            // Draw Dots for steps if not too many
            if (steps in 2..12) {
                for (i in 0 until steps) {
                    val dotX = horizontalPaddingPx + i * (availableWidth / (steps - 1))
                    drawCircle(
                        color = Color.White.copy(0.2f),
                        radius = 2.dp.toPx(),
                        center = Offset(dotX, size.height / 2)
                    )
                }
            }
            
            // Thumb
            drawRoundRect(
                color = Color(0xFFFFB2AD),
                topLeft = Offset(thumbX - 3.dp.toPx(), 14.dp.toPx()),
                size = Size(6.dp.toPx(), size.height - 28.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }
    }
}

@Composable
fun CustomTimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(30) }
    var seconds by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF1E1B1B),
            border = BorderStroke(1.dp, Color.White.copy(0.1f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    "Custom Duration",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeValuePicker("HH", hours) { hours = it.coerceIn(0, 23) }
                    Text(":", color = Color.White.copy(0.3f), fontSize = 32.sp, fontWeight = FontWeight.Light)
                    TimeValuePicker("MM", minutes) { minutes = it.coerceIn(0, 59) }
                    Text(":", color = Color.White.copy(0.3f), fontSize = 32.sp, fontWeight = FontWeight.Light)
                    TimeValuePicker("SS", seconds) { seconds = it.coerceIn(0, 59) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(54.dp)
                    ) {
                        Text("Cancel", color = Color.White.copy(0.6f))
                    }
                    Button(
                        onClick = {
                            val totalSeconds = (hours * 3600) + (minutes * 60) + seconds
                            if (totalSeconds > 0) onConfirm(totalSeconds)
                        },
                        modifier = Modifier.weight(1.5f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB2AD))
                    ) {
                        Text("Set Timer", color = Color(0xFF331D1B), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeValuePicker(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { onValueChange(value + 1) }) {
            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Color.White.copy(0.5f))
        }
        Column(
            modifier = Modifier
                .width(60.dp)
                .height(70.dp)
                .background(Color.White.copy(0.06f), RoundedCornerShape(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "%02d".format(value),
                color = Color(0xFFFFB2AD),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Text(label, color = Color.White.copy(0.3f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { onValueChange(value - 1) }) {
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White.copy(0.5f))
        }
    }
}
