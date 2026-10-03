package com.beatraxus.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.beatraxus.app.model.Video
import com.beatraxus.app.utils.VideoThumbnailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems

import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.beatraxus.app.model.VideoRecentlyPlayedEntity

@Composable
fun VideoLibraryScreen(
    videos: List<Video>,
    continueWatching: List<Pair<Video, VideoRecentlyPlayedEntity>>,
    isRefreshing: Boolean,
    columns: Int = 1,
    selectedIds: Set<String> = emptySet(),
    isMultiSelectMode: Boolean = false,
    onRefresh: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onVideoLongClick: (Video) -> Unit = {}
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState(),
        modifier = Modifier.fillMaxSize()
    ) {
        if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Movie, null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.2f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No videos found",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            if (columns == 1) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 140.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (continueWatching.isNotEmpty()) {
                        item {
                            ContinueWatchingRow(continueWatching, onVideoClick)
                        }
                    }
                    items(videos, key = { it.id }) { video ->
                        VideoListItem(
                            video = video,
                            isSelected = selectedIds.contains(video.id),
                            isMultiSelectMode = isMultiSelectMode,
                            onClick = { onVideoClick(video) },
                            onLongClick = { onVideoLongClick(video) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (continueWatching.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueWatchingRow(continueWatching, onVideoClick)
                        }
                    }
                    gridItems(videos, key = { it.id }) { video ->
                        VideoItem(
                            video = video,
                            isSelected = selectedIds.contains(video.id),
                            isMultiSelectMode = isMultiSelectMode,
                            onClick = { onVideoClick(video) },
                            onLongClick = { onVideoLongClick(video) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingRow(
    items: List<Pair<Video, VideoRecentlyPlayedEntity>>,
    onVideoClick: (Video) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            "Continue Watching",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(items, key = { it.first.id }) { (video, entity) ->
                ContinueWatchingItem(
                    video = video,
                    progress = if (entity.durationMs > 0) entity.lastPositionMs.toFloat() / entity.durationMs else 0f,
                    onClick = { onVideoClick(video) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ContinueWatchingItem(
    video: Video,
    progress: Float,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailUri by remember(video.id) { mutableStateOf(video.thumbnailUri) }

    LaunchedEffect(video.id, video.thumbnailUri) {
        val cachedUri = video.thumbnailUri
        if (cachedUri == null || !VideoThumbnailHelper.thumbnailExists(context, video.id)) {
            thumbnailUri = VideoThumbnailHelper.getThumbnail(context, video.uri, video.id)
        } else {
            thumbnailUri = cachedUri
        }
    }

    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            AsyncImage(
                model = thumbnailUri ?: video.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { thumbnailUri = null }
            )

            // Progress Bar at the bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        
        Spacer(Modifier.height(6.dp))
        
        Text(
            text = video.title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ───────────────────────── Video metadata helpers ─────────────────────────

private data class ResTier(val label: String, val color: Color)

/** Quality tier from the short side of the frame, so portrait clips are classified correctly. */
private fun resolutionTier(video: Video): ResTier {
    val shortSide = minOf(video.resolutionWidth, video.resolutionHeight)
    val longSide = maxOf(video.resolutionWidth, video.resolutionHeight)
    return when {
        shortSide >= 2160 || longSide >= 3840 -> ResTier("4K", Color(0xFFFFB300))
        shortSide >= 1440 -> ResTier("2K", Color(0xFFBF5AF2))
        shortSide >= 1080 -> ResTier("FHD", Color(0xFF0A84FF))
        shortSide >= 720 -> ResTier("HD", Color(0xFF30D158))
        shortSide > 0 -> ResTier("SD", Color(0xFF8E8E93))
        else -> ResTier("", Color(0xFF8E8E93))
    }
}

private fun resolutionText(video: Video): String =
    if (video.resolutionWidth > 0 && video.resolutionHeight > 0)
        "${video.resolutionWidth}\u00D7${video.resolutionHeight}" else "Unknown"

private fun containerFormat(video: Video): String {
    val ext = video.displayName.substringAfterLast('.', "")
    if (ext.isNotEmpty() && ext.length <= 4) return ext.uppercase()
    return when (video.mimeType.lowercase()) {
        "video/mp4" -> "MP4"
        "video/x-matroska" -> "MKV"
        "video/webm" -> "WEBM"
        "video/3gpp" -> "3GP"
        "video/quicktime" -> "MOV"
        "video/x-msvideo" -> "AVI"
        else -> video.mimeType.substringAfter('/', "VIDEO").uppercase()
    }
}

private fun aspectText(video: Video): String? {
    val w = video.resolutionWidth
    val h = video.resolutionHeight
    if (w <= 0 || h <= 0) return null
    val r = w.toFloat() / h
    return when {
        kotlin.math.abs(r - 16f / 9f) < 0.03f -> "16:9"
        kotlin.math.abs(r - 9f / 16f) < 0.03f -> "9:16"
        kotlin.math.abs(r - 4f / 3f) < 0.03f -> "4:3"
        kotlin.math.abs(r - 21f / 9f) < 0.08f -> "21:9"
        kotlin.math.abs(r - 1f) < 0.03f -> "1:1"
        else -> null
    }
}

private fun isNewVideo(video: Video): Boolean =
    System.currentTimeMillis() / 1000 - video.dateAdded < 24 * 60 * 60

private fun addedAgoText(video: Video): String {
    val diff = (System.currentTimeMillis() - (video.dateAdded * 1000)) / (1000 * 60 * 60 * 24)
    return when {
        diff <= 0L -> "Added today"
        diff == 1L -> "Added yesterday"
        diff < 30L -> "Added $diff days ago"
        diff < 365L -> "Added ${diff / 30} mo ago"
        else -> "Added ${diff / 365} yr ago"
    }
}

@Composable
private fun rememberVideoThumbnail(video: Video): androidx.compose.runtime.MutableState<android.net.Uri?> {
    val context = LocalContext.current
    val state = remember(video.id) { mutableStateOf(video.thumbnailUri) }
    LaunchedEffect(video.id, video.thumbnailUri) {
        val cachedUri = video.thumbnailUri
        state.value = if (cachedUri == null || !VideoThumbnailHelper.thumbnailExists(context, video.id)) {
            VideoThumbnailHelper.getThumbnail(context, video.uri, video.id)
        } else cachedUri
    }
    return state
}

/** Small rounded info chip with optional leading icon. */
@Composable
private fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = Color.White.copy(alpha = 0.78f),
    filled: Boolean = false
) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (filled) tint.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (filled) tint.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text = text,
            color = if (filled) tint else Color.White.copy(alpha = 0.82f),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Thumbnail with duration, resolution tier, NEW / HDR badges and multi-select overlay. */
@Composable
private fun VideoThumbnail(
    video: Video,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp
) {
    var thumbnailUri by rememberVideoThumbnail(video)
    val tier = remember(video.resolutionWidth, video.resolutionHeight) { resolutionTier(video) }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
    ) {
        AsyncImage(
            model = thumbnailUri ?: video.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { thumbnailUri = null }
        )

        // bottom scrim so the badges stay readable on bright frames
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
        )

        // resolution tier (bottom-left)
        if (tier.label.isNotEmpty()) {
            Text(
                text = tier.label,
                color = Color.Black,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tier.color)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        // duration (bottom-right)
        Text(
            text = formatDuration(video.durationMs),
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )

        if (isNewVideo(video)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(bottomEnd = 8.dp))
                    .background(Color(0xFFFF3B30))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            }
        }

        if (video.isHdr) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(bottomStart = 8.dp))
                    .background(Color(0xFFFFD54F))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("HDR", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            }
        }

        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.Black.copy(alpha = 0.4f) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(28.dp))
                } else {
                    Box(Modifier.size(20.dp).border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
    }
}

/** Grid card: thumbnail, title, one detail line (resolution · format · size) and folder. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoItem(
    video: Video,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val tier = remember(video.resolutionWidth, video.resolutionHeight) { resolutionTier(video) }
    val cardShape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.07f),
                cardShape
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        VideoThumbnail(
            video = video,
            isSelected = isSelected,
            isMultiSelectMode = isMultiSelectMode,
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 11.dp
        )

        Spacer(Modifier.height(7.dp))

        Text(
            text = video.title,
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp)
        )

        Spacer(Modifier.height(3.dp))

        Text(
            text = listOfNotNull(
                resolutionText(video),
                containerFormat(video),
                formatFileSize(video.sizeBytes)
            ).joinToString("  \u2022  "),
            color = tier.color.copy(alpha = 0.95f),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp)
        )

        Row(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Folder, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                text = video.folderPath.trimEnd('/').substringAfterLast("/"),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** List card: big thumbnail on the left, full detail block (resolution, format, size, folder, date) on the right. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun VideoListItem(
    video: Video,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val tier = remember(video.resolutionWidth, video.resolutionHeight) { resolutionTier(video) }
    val aspect = remember(video.resolutionWidth, video.resolutionHeight) { aspectText(video) }
    val cardShape = RoundedCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.03f))
                )
            )
            .border(
                1.dp,
                if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                cardShape
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        VideoThumbnail(
            video = video,
            isSelected = isSelected,
            isMultiSelectMode = isMultiSelectMode,
            modifier = Modifier.width(150.dp),
            cornerRadius = 11.dp
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = video.folderPath.trimEnd('/').substringAfterLast("/"),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(7.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (tier.label.isNotEmpty()) {
                    InfoChip(text = tier.label, tint = tier.color, filled = true)
                }
                InfoChip(text = resolutionText(video), icon = Icons.Rounded.AspectRatio)
                if (aspect != null) InfoChip(text = aspect)
                InfoChip(text = containerFormat(video), icon = Icons.Rounded.Movie)
                if (video.isHdr) InfoChip(text = "HDR", tint = Color(0xFFFFD54F), filled = true)
                InfoChip(text = formatFileSize(video.sizeBytes), icon = Icons.Rounded.Storage)
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarToday, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${addedAgoText(video)}  \u2022  ${formatDateShort(video.dateAdded * 1000)}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

private fun formatDateShort(timestampMs: Long): String {
    val sdf = SimpleDateFormat("d MMM", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}
