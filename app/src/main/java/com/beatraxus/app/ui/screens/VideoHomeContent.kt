package com.beatraxus.app.ui.screens

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.LibraryView
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
import com.beatraxus.app.utils.VideoThumbnailHelper
import com.beatraxus.app.viewmodel.PlayerViewModel

private val CinemaCyan = Color(0xFF00F2FF)
private val CinemaBlue = Color(0xFF0A84FF)
private val CinemaAmber = Color(0xFFFFAB40)

/**
 * Video-mode home. The "play last video" button now lives INSIDE the hero card
 * (bottom-right corner of the card) instead of floating over the whole screen.
 *
 * Call from HomeScreen's LazyColumn:  videoHomeItems(viewModel, uiState, videos, videoFolders, greeting)
 */
fun LazyListScope.videoHomeItems(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    videos: List<Video>,
    videoFolders: List<com.beatraxus.app.model.VideoFolder>,
    greeting: String,
    onConnectCloudVideo: (com.beatraxus.app.ui.components.CloudVideoProvider) -> Unit = {}
) {
    val libMode = uiState.libraryMode
    val resumePair = uiState.continueWatching.firstOrNull()
    val heroVideo = resumePair?.first ?: videos.firstOrNull()

    // ── Greeting + stats ────────────────────────────────────────────────
    item(key = "VIDEO_GREETING") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = greeting.uppercase(),
                color = CinemaCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your Cinema",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(14.dp))
            val totalMs = remember(videos) { videos.sumOf { it.durationMs } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("${videos.size} videos")
                StatPill("${videoFolders.size} folders")
                if (totalMs > 0) StatPill(formatHours(totalMs))
            }
        }
    }

    // ── Cloud / Combined: connect cloud videos ───────────────────────────
    if (libMode != com.beatraxus.app.model.LibraryMode.LOCAL) {
        item(key = "VIDEO_CLOUD_CONNECT") {
            com.beatraxus.app.ui.components.CloudVideoConnectCard(uiState, onConnectCloudVideo)
            Spacer(Modifier.height(12.dp))
        }
        // Cloud-only: there are no cloud videos to list yet, so hide the local sections.
        if (libMode == com.beatraxus.app.model.LibraryMode.CLOUD) return
    }

    // ── Hero card with integrated play button ───────────────────────────
    if (heroVideo != null) {
        item(key = "VIDEO_HERO") {
            VideoHeroCard(
                video = heroVideo,
                entity = resumePair?.second,
                onPlay = { viewModel.playVideo(heroVideo) }
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    // ── Library shortcuts ───────────────────────────────────────────────
    item(key = "VIDEO_SHORTCUTS") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile(
                    "All Videos", Icons.Rounded.VideoLibrary, CinemaBlue,
                    Modifier.weight(1f)
                ) { viewModel.setLibraryView(LibraryView.VIDEO_ALL) }
                ShortcutTile(
                    "Folders", Icons.Rounded.FolderCopy, CinemaAmber,
                    Modifier.weight(1f)
                ) { viewModel.setLibraryView(LibraryView.VIDEO_FOLDERS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile(
                    "Recently Added", Icons.Rounded.NewReleases, Color(0xFFFF5E62),
                    Modifier.weight(1f)
                ) { viewModel.setLibraryView(LibraryView.VIDEO_RECENTLY_ADDED) }
                ShortcutTile(
                    "Recently Played", Icons.Rounded.History, Color(0xFFBF5AF2),
                    Modifier.weight(1f)
                ) { viewModel.setLibraryView(LibraryView.VIDEO_RECENTLY_PLAYED) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── Continue watching ───────────────────────────────────────────────
    if (uiState.continueWatching.size > 1) {
        item(key = "VIDEO_CONTINUE") {
            SectionTitle("Continue Watching", "Pick up where you left off")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.continueWatching.drop(1), key = { it.first.id }) { (video, entity) ->
                    ContinueCard(video, entity) { viewModel.playVideo(video) }
                }
            }
        }
    }

    // ── Folders ─────────────────────────────────────────────────────────
    if (videoFolders.isNotEmpty()) {
        item(key = "VIDEO_FOLDERS_HOME") {
            SectionTitle("Folders", "${videoFolders.size} locations") {
                viewModel.setLibraryView(LibraryView.VIDEO_FOLDERS)
            }
            val palette = listOf(
                CinemaAmber, CinemaBlue, Color(0xFFBF5AF2), Color(0xFF30D158), Color(0xFFFF5E62)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(videoFolders.take(8).withIndex().toList()) { (i, folder) ->
                    FolderTile(
                        name = folder.name,
                        count = folder.videoCount,
                        accent = palette[i % palette.size]
                    ) { viewModel.navigateToVideoFolder(folder.path, folder.name) }
                }
            }
        }
    }

    // ── Latest videos (2-up grid) ───────────────────────────────────────
    if (videos.isNotEmpty()) {
        item(key = "VIDEO_LATEST_TITLE") {
            SectionTitle("Latest Videos", "See all") {
                viewModel.setLibraryView(LibraryView.VIDEO_ALL)
            }
        }
        items(videos.take(10).chunked(2), key = { row -> "vrow_" + row.first().id }) { pair ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { v ->
                    GridVideoCard(v, Modifier.weight(1f)) { viewModel.playVideo(v) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Hero card  (play button is part of the card, bottom-right corner)
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun VideoHeroCard(
    video: Video,
    entity: VideoRecentlyPlayedEntity?,
    onPlay: () -> Unit
) {
    val thumb = rememberVideoThumb(video)
    val progress = if (entity != null && entity.durationMs > 0)
        (entity.lastPositionMs.toFloat() / entity.durationMs).coerceIn(0f, 1f) else 0f
    // Any saved history means this card is the "last played" video.
    val hasHistory = entity != null
    val isResume = entity != null && entity.lastPositionMs > 0
    val leftMs = if (entity != null && entity.durationMs > 0)
        (entity.durationMs - entity.lastPositionMs).coerceAtLeast(0) else video.durationMs

    val pulse = rememberInfiniteTransition(label = "heroPulse")
    val ring by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(20.dp, RoundedCornerShape(30.dp), ambientColor = CinemaCyan, spotColor = CinemaBlue)
            .clip(RoundedCornerShape(30.dp))
            .aspectRatio(16f / 10.5f)
            .background(Color(0xFF0B1220))
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(0.28f), Color.White.copy(0.04f)))
                ),
                RoundedCornerShape(30.dp)
            )
            .clickable(onClick = onPlay)
    ) {
        AsyncImage(
            model = thumb ?: video.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // cinematic scrims
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(0.35f),
                    0.35f to Color.Transparent,
                    1f to Color.Black.copy(0.92f)
                )
            )
        )

        // label chip
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(0.45f))
                .border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(CinemaCyan, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasHistory) "LAST PLAYED" else "START WATCHING",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
        }

        // title / meta  (bottom-left)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 104.dp, bottom = 26.dp)
        ) {
            Text(
                video.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AccessTime, null,
                    tint = Color.White.copy(0.6f), modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isResume) "Resume ${formatClock(entity!!.lastPositionMs)}  ·  ${formatClock(leftMs)} left" else formatClock(video.durationMs),
                    color = Color.White.copy(0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (video.resolutionHeight > 0) {
                    Text("  ·  ${qualityLabel(video.resolutionHeight)}", color = Color.White.copy(0.5f), fontSize = 12.sp)
                }
            }
        }

        // ▶ integrated play button (bottom-right corner of the card)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp)
                .size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            // soft expanding ring
            Box(
                Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        val s = 0.75f + ring * 0.45f
                        scaleX = s; scaleY = s; alpha = (1f - ring) * 0.55f
                    }
                    .border(2.dp, CinemaCyan, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .shadow(12.dp, CircleShape, ambientColor = CinemaCyan, spotColor = CinemaCyan)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(CinemaCyan, CinemaBlue)))
                    .clickable(role = Role.Button, onClick = onPlay)
                    .semantics { contentDescription = if (isResume) "Resume last video" else "Play video" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(34.dp))
            }
        }

        // progress line
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(0.18f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(CinemaCyan, CinemaBlue)))
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Small building blocks
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun StatPill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(0.07f))
            .border(0.5.dp, Color.White.copy(0.12f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = Color.White.copy(0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(title: String, sub: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(Brush.verticalGradient(listOf(CinemaCyan, CinemaBlue)), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        if (sub != null) {
            Text(
                sub,
                color = if (onClick != null) CinemaCyan else Color.White.copy(0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
        }
    }
}

@Composable
private fun ShortcutTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(accent.copy(0.20f), Color.White.copy(0.04f))))
            .border(0.5.dp, accent.copy(0.30f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(accent.copy(0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label, color = Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContinueCard(
    video: Video,
    entity: VideoRecentlyPlayedEntity,
    onClick: () -> Unit
) {
    val thumb = rememberVideoThumb(video)
    val progress = if (entity.durationMs > 0)
        (entity.lastPositionMs.toFloat() / entity.durationMs).coerceIn(0f, 1f) else 0f
    val left = (entity.durationMs - entity.lastPositionMs).coerceAtLeast(0)

    Column(Modifier.width(230.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(0.05f))
        ) {
            AsyncImage(
                model = thumb ?: video.uri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(0.75f))
                )
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(34.dp)
                    .background(Color.Black.copy(0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color.White.copy(0.2f))
            ) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(CinemaCyan))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            video.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            "${formatClock(left)} left", color = Color.White.copy(0.5f), fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun FolderTile(name: String, count: Int, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(168.dp)
            .height(104.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(accent.copy(0.30f), Color.Black.copy(0.15f))))
            .border(0.5.dp, accent.copy(0.35f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
    ) {
        Icon(
            Icons.Rounded.Folder, null, tint = accent.copy(0.22f),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).size(72.dp)
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("$count videos", color = Color.White.copy(0.6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun GridVideoCard(video: Video, modifier: Modifier, onClick: () -> Unit) {
    val thumb = rememberVideoThumb(video)
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(0.05f))
        ) {
            AsyncImage(
                model = thumb ?: video.uri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(0.6f))
                )
            )
            Text(
                formatClock(video.durationMs),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(0.65f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (video.isHdr) {
                Text(
                    "HDR", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0xFFFFD54F), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            video.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun rememberVideoThumb(video: Video): Uri? {
    val context = LocalContext.current
    var thumb by remember(video.id) { mutableStateOf(video.thumbnailUri) }
    LaunchedEffect(video.id, video.thumbnailUri) {
        val cached = video.thumbnailUri
        thumb = if (cached == null || !VideoThumbnailHelper.thumbnailExists(context, video.id)) {
            VideoThumbnailHelper.getThumbnail(context, video.uri, video.id)
        } else cached
    }
    return thumb
}

private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatHours(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return if (h > 0) "${h}h ${m}m total" else "${m}m total"
}

private fun qualityLabel(height: Int): String = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    else -> "${height}p"
}
