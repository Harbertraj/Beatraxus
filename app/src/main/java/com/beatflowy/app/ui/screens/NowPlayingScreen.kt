package com.beatflowy.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.components.WaveformSeekBar
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
    showLyrics: Boolean,
    lyrics: List<Pair<Long, String>>,
    uiState: com.beatflowy.app.model.PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onPlayFromQueue: (String) -> Unit,
    upcomingSongs: List<Song>,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit
) {
    if (song == null) return
    val showQueue = uiState.showQueue

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Vibrant Windows 11 Acrylic/Mica Background
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.65f
                }
                .blur(80.dp),
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

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Text(
                            if (showQueue) "Queue" else "Now Playing",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                letterSpacing = 1.sp
                            ),
                            color = Color.White
                        )
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
                    },
                    actions = { Box(Modifier.size(48.dp)) }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .pointerInput(showQueue, showLyrics) {
                        if (!showQueue && !showLyrics) {
                            var totalY = 0f
                            detectVerticalDragGestures(
                                onDragStart = { totalY = 0f },
                                onDragEnd = {
                                    if (totalY > 100) onClose()
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    totalY += dragAmount
                                    if (abs(totalY) > 50) change.consume()
                                }
                            )
                        }
                    },
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
                    Spacer(Modifier.height(12.dp))

                    // Album Art with Swipe & Interaction
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(horizontal = 4.dp)
                    ) {
                        if (showLyrics) {
                            LyricsView(songId = song.id, progressProvider = progressMs, lyrics = lyrics)
                        } else {
                            var isTouching by remember { mutableStateOf(false) }
                            val scaleState = animateFloatAsState(
                                targetValue = if (isTouching) 0.92f else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                label = "scale"
                            )

                            // Simplified Shared Element-like transition for Album Art
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val s = scaleState.value
                                        scaleX = s
                                        scaleY = s
                                    }
                                    .shadow(32.dp, RoundedCornerShape(32.dp))
                                    .clip(RoundedCornerShape(32.dp))
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                isTouching = true
                                                tryAwaitRelease()
                                                isTouching = false
                                            }
                                        )
                                    }
                                    .pointerInput(Unit) {
                                        var totalX = 0f
                                        var totalY = 0f
                                        detectDragGestures(
                                            onDragStart = { 
                                                totalX = 0f
                                                totalY = 0f
                                            },
                                            onDragEnd = {
                                                if (abs(totalX) > abs(totalY)) {
                                                    if (totalX > 50) onPrevious()
                                                    else if (totalX < -50) onNext()
                                                } else {
                                                    if (totalY > 100) onClose()
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                totalX += dragAmount.x
                                                totalY += dragAmount.y
                                            }
                                        )
                                    }
                            ) {
                                AnimatedContent(
                                    targetState = song.albumArtUri,
                                    transitionSpec = {
                                        (fadeIn() + scaleIn(initialScale = 0.9f)).togetherWith(fadeOut() + scaleOut(targetScale = 1.1f))
                                    },
                                    label = "artTransition"
                                ) { artUri ->
                                    AsyncImage(
                                        model = artUri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Audio Quality Badge
                    if (!showLyrics) {
                        AudioQualityBadge(song)
                    } else {
                        Spacer(Modifier.height(26.dp))
                    }

                    Spacer(Modifier.height(16.dp))

                    // Song Info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onFavoriteClick) {
                            Icon(
                                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                null,
                                tint = if (isFavorite) Color(0xFFFF4081) else Color.White.copy(alpha = 0.8f)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${song.artist} • ${song.album}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onNavigateToAlbum(song.album) }
                            )
                        }
                        IconButton(onClick = onToggleLyrics) {
                            Icon(
                                if (showLyrics) Icons.Rounded.ArtTrack else Icons.Rounded.Lyrics,
                                null,
                                tint = if (showLyrics) Color(0xFFFFD700) else Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // SeekBar
                    val progress = if (durationMs > 0) (progressMs().toFloat() / durationMs) else 0f
                    WaveformSeekBar(
                        progress = progress,
                        onProgressChange = { onSeek((it * durationMs).toLong()) },
                        activeColor = Color.White,
                        inactiveColor = Color.White.copy(0.2f),
                        seed = song.id.hashCode(),
                        progressPollKey = progressMs() / 200,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fmtTime(progressMs()), color = Color.White.copy(0.5f), fontSize = 12.sp)
                        TechnicalInfo(song)
                        Text(fmtTime(durationMs), color = Color.White.copy(0.5f), fontSize = 12.sp)
                    }

                    Spacer(Modifier.weight(1f))

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrevious) {
                            Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                        Spacer(Modifier.width(28.dp))
                        Surface(
                            modifier = Modifier.size(76.dp).clickable { onPlayPause() },
                            shape = CircleShape,
                            color = Color.White
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(42.dp))
                            }
                        }
                        Spacer(Modifier.width(28.dp))
                        IconButton(onClick = onNext) {
                            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }
                }

                // Bottom Control Pill
                Surface(
                    shape = RoundedCornerShape(40.dp),
                    color = Color.White.copy(alpha = 0.1f),
                    border = BorderStroke(0.5.dp, Color.White.copy(0.1f)),
                    modifier = Modifier.padding(bottom = 24.dp).fillMaxWidth(0.9f).height(72.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onShuffle) {
                            Icon(Icons.Rounded.Shuffle, null, tint = if (shuffleMode) Color(0xFF40C4FF) else Color.White.copy(0.8f), modifier = Modifier.size(26.dp))
                        }
                        Box(Modifier.width(1.dp).height(32.dp).background(Color.White.copy(0.1f)))
                        IconButton(onClick = onRepeat) {
                            Icon(if (repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if (repeatMode != 0) Color(0xFF40C4FF) else Color.White.copy(0.8f), modifier = Modifier.size(26.dp))
                        }
                        Box(Modifier.width(1.dp).height(32.dp).background(Color.White.copy(0.1f)))
                        IconButton(onClick = onOpenEqualizer) {
                            Icon(Icons.Rounded.Equalizer, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(26.dp))
                        }
                        Box(Modifier.width(1.dp).height(32.dp).background(Color.White.copy(0.1f)))
                        IconButton(onClick = onToggleQueue) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = if (showQueue) Color(0xFF40C4FF) else Color.White.copy(0.8f), modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioQualityBadge(song: Song) {
    val format = song.format.lowercase()
    val isLosslessFormat = format == "flac" || format == "alac" || format == "wav" || format == "m4a"
    val isHiRes = isLosslessFormat && song.bitDepth >= 24
    val isLossless = isLosslessFormat && song.bitDepth == 16
    
    val badgeColor = when {
        isHiRes -> Color(0xFFFFD700)
        isLossless -> Color(0xFFFFD700)
        else -> Color(0xFFC0C0C0)
    }

    val label = when {
        isHiRes -> "Hi-Res Lossless"
        isLossless -> "Lossless"
        else -> "Lossy"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate = infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer"
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = badgeColor.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .drawWithContent {
                drawContent()
                if (isHiRes) {
                    val currentTranslate = shimmerTranslate.value
                    val brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(0.4f), Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(currentTranslate, 0f),
                        end = androidx.compose.ui.geometry.Offset(currentTranslate + 100f, 100f)
                    )
                    drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = when {
                    isHiRes -> Icons.Rounded.HighQuality
                    isLossless -> Icons.Rounded.WorkspacePremium
                    else -> Icons.Rounded.Layers
                },
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
                color = badgeColor
            )
        }
    }
}

@Composable
fun TechnicalInfo(song: Song) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.08f)) {
        val info = buildString {
            if (song.bitDepth > 0) append("${song.bitDepth}bit ")
            append("${String.format(Locale.US, "%.1f", song.sampleRateHz / 1000.0)}kHz ")
            if (song.bitrate > 0) append("${song.bitrate / 1000}kbps ")
            append(song.format.lowercase())
        }
        Text(
            text = info,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun LyricsView(songId: String, progressProvider: () -> Long, lyrics: List<Pair<Long, String>>) {
    val listState = rememberLazyListState()
    val hasTimedLines = remember(lyrics) {
        lyrics.any { it.first > 0L } || lyrics.size > 1
    }

    LaunchedEffect(songId) {
        listState.scrollToItem(0)
    }

    if (lyrics.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No lyrics available",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(0.45f),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (!hasTimedLines) {
        val body = remember(lyrics) { lyrics.joinToString("\n\n") { it.second } }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                body,
                style = MaterialTheme.typography.titleMedium.copy(lineHeight = 26.sp),
                color = Color.White.copy(0.85f),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val activeIndex by remember(lyrics) {
        derivedStateOf {
            val progress = progressProvider()
            lyrics.indexOfLast { it.first <= progress }.coerceAtLeast(0)
        }
    }

    LaunchedEffect(activeIndex, songId) {
        if (lyrics.isNotEmpty()) {
            val target = activeIndex.coerceIn(0, (lyrics.size - 1).coerceAtLeast(0))
            listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics, key = { index, _ -> "${songId}_$index" }) { index, line ->
            val isActive = index == activeIndex
            val scaleState = animateFloatAsState(
                targetValue = if (isActive) 1.2f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                label = "lyricScale"
            )
            Text(
                text = line.second,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                    fontSize = 20.sp
                ),
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.32f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .graphicsLayer {
                        val s = scaleState.value
                        scaleX = s
                        scaleY = s
                        alpha = if (isActive) 1f else 0.45f
                    }
            )
        }
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
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Now Playing Section (Tappable to go back)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clickable { onClose() },
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = currentSong.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        currentSong.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${currentSong.artist} • ${currentSong.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Up Next Header (Clickable to go back)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClose() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "UP NEXT",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = Color.White
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.15f)
            ) {
                Text(
                    upcomingSongs.size.toString(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
        
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.1f))
        )

        // Queue List
        if (upcomingSongs.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No upcoming songs",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        } else {
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                onMove(from.index, to.index)
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(upcomingSongs, key = { song -> song.id }) { song ->
                    ReorderableItem(reorderableState, key = song.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation, RoundedCornerShape(20.dp))
                                .clickable { onPlayFromQueue(song.id) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isDragging) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = "Reorder",
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .draggableHandle()
                                )
                                
                                Spacer(Modifier.width(16.dp))
                                
                                AsyncImage(
                                    model = song.albumArtUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                
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
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                IconButton(
                                    onClick = { onRemoveFromQueue(song.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFB00020).copy(alpha = 0.2f),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                null,
                                                tint = Color(0xFFCF6679),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
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
