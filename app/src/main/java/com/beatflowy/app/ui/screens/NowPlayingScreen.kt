package com.beatflowy.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.components.AlbumArtImage
import com.beatflowy.app.ui.components.WaveformSeekBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    song: Song?,
    isPlaying: Boolean,
    progressMs: Long,
    durationMs: Long,
    shuffleMode: Boolean,
    repeatMode: Int,
    showLyrics: Boolean,
    lyrics: List<Pair<Long, String>>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleLyrics: () -> Unit
) {
    if (song == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(showLyrics) {
                if (!showLyrics) {
                    var totalDragY = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { totalDragY = 0f },
                        onDragCancel = { totalDragY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            totalDragY += dragAmount
                            if (totalDragY > 150f) {
                                onClose()
                            }
                        }
                    )
                }
            }
    ) {
        // Vibrant Gradient Background derived from Album Art
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(70.dp)
                .graphicsLayer(alpha = 0.55f),
            contentScale = ContentScale.Crop,
        )
        
        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    title = {
                        Text(
                            "Now Playing",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    },
                    actions = {
                        Spacer(Modifier.width(48.dp))
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
                Spacer(Modifier.height(8.dp))

                // Crossfade between Album Art and Lyrics
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Crossfade(targetState = showLyrics, label = "art_lyrics_crossfade") { showingLyrics ->
                        if (showingLyrics) {
                            LyricsView(progressMs, lyrics)
                        } else {
                            var offsetX = 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shadow(
                                        elevation = 32.dp,
                                        shape = RoundedCornerShape(36.dp),
                                        clip = false
                                    )
                                    .clip(RoundedCornerShape(36.dp))
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = { offsetX = 0f },
                                            onDragCancel = { offsetX = 0f },
                                            onHorizontalDrag = { _, dragAmount ->
                                                offsetX += dragAmount
                                                if (offsetX > 200f) {
                                                    onPrevious()
                                                    offsetX = 0f
                                                } else if (offsetX < -200f) {
                                                    onNext()
                                                    offsetX = 0f
                                                }
                                            }
                                        )
                                    }
                            ) {
                                AlbumArtImage(song = song, size = 400.dp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Quality Badge Row
                val format = song.format.lowercase()
                val isHiRes = (format == "flac" || format == "alac") && song.bitDepth >= 24
                val isLossless = (format == "flac" || format == "alac") && song.bitDepth == 16

                val badgeColor = when {
                    isHiRes -> Color(0xFFFFD700) // Golden
                    isLossless -> Color(0xFFCD7F32) // Bronze
                    else -> Color(0xFFC0C0C0) // Silver
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
                    modifier = Modifier.shadow(
                        elevation = if (isHiRes) 20.dp else 10.dp,
                        shape = RoundedCornerShape(8.dp),
                        ambientColor = badgeColor,
                        spotColor = badgeColor
                    )
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
                            text = when {
                                isHiRes -> "Hi-Res Lossless"
                                isLossless -> "Lossless"
                                else -> "Lossy"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            ),
                            color = badgeColor
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Song Info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${song.artist} • ${song.album}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    Row {
                        IconButton(onClick = onToggleLyrics) {
                            Icon(
                                if (showLyrics) Icons.Rounded.ArtTrack else Icons.Rounded.Lyrics,
                                null,
                                tint = if (showLyrics) Color(0xFFFFD700) else Color.White.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.MoreVert, null, tint = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Waveform SeekBar
                val progress = if (durationMs > 0) (progressMs.toFloat() / durationMs) else 0f
                WaveformSeekBar(
                    progress = progress,
                    onProgressChange = { onSeek((it * durationMs).toLong()) },
                    activeColor = Color.White,
                    inactiveColor = Color.White.copy(alpha = 0.2f),
                    seed = song.id.hashCode(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                )

                // Time and Technical Info Row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fmtTime(progressMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.width(48.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                    ) {
                        Text(
                            text = formatSongInfo(song),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }

                    Text(
                        text = fmtTime(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(48.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                // Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                    
                    Spacer(Modifier.width(24.dp))
                    
                    Surface(
                        modifier = Modifier
                            .size(76.dp)
                            .clickable { onPlayPause() },
                        shape = CircleShape,
                        color = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null,
                                tint = Color.Black,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(24.dp))

                    IconButton(onClick = onNext) {
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Bottom Control Pill (Shuffle, Repeat, Equalizer)
                Surface(
                    shape = RoundedCornerShape(40.dp),
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .fillMaxWidth(0.9f)
                        .height(84.dp) // Increased height from 72.dp to 84.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle
                        IconButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                null,
                                tint = if (shuffleMode) Color(0xFFFFD700) else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        // Separator
                        Box(Modifier.width(1.dp).height(36.dp).background(Color.White.copy(alpha = 0.1f)))

                        // Repeat
                        IconButton(onClick = onRepeat, modifier = Modifier.weight(1f)) {
                            val icon = when(repeatMode) {
                                1 -> Icons.Rounded.RepeatOne
                                else -> Icons.Rounded.Repeat
                            }
                            Icon(
                                icon,
                                null,
                                tint = if (repeatMode != 0) Color(0xFFFFD700) else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Separator
                        Box(Modifier.width(1.dp).height(36.dp).background(Color.White.copy(alpha = 0.1f)))

                        // Equalizer
                        IconButton(onClick = onOpenEqualizer, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Rounded.Equalizer,
                                null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsView(currentMs: Long, lyrics: List<Pair<Long, String>>) {
    val listState = rememberLazyListState()
    
    // Find active line
    val activeIndex = lyrics.indexOfLast { it.first <= currentMs }.coerceAtLeast(0)

    LaunchedEffect(activeIndex) {
        if (lyrics.isNotEmpty()) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -300)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics) { index, line ->
            val isActive = index == activeIndex
            Text(
                text = line.second,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                    fontSize = if (isActive) 22.sp else 18.sp
                ),
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .graphicsLayer(alpha = if (isActive) 1f else 0.6f)
            )
        }
    }
}

private fun formatSongInfo(song: Song): String {
    val khz = "%.1f".format(song.sampleRateHz / 1000.0)
    val kbpsValue = if (song.bitrate > 0) song.bitrate / 1000 else 0
    val format = song.format.lowercase()
    val bitDepth = if (song.bitDepth > 16) "|${song.bitDepth}bit" else ""
    
    return "$format|$khz" + "kHz" + (if (kbpsValue > 0) "|$kbpsValue" + "kbps" else "") + bitDepth
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}
