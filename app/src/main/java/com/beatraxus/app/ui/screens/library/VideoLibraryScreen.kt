package com.beatraxus.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun VideoLibraryScreen(
    videos: List<Video>,
    isRefreshing: Boolean,
    columns: Int = 1,
    onRefresh: () -> Unit,
    onVideoClick: (Video) -> Unit
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
                    items(videos, key = { it.id }) { video ->
                        VideoListItem(video = video, onClick = { onVideoClick(video) })
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
                    gridItems(videos, key = { it.id }) { video ->
                        VideoItem(video = video, onClick = { onVideoClick(video) })
                    }
                }
            }
        }
    }
}

@Composable
fun VideoItem(
    video: Video,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailUri by remember(video.id) { mutableStateOf(video.thumbnailUri) }

    LaunchedEffect(video.id, video.thumbnailUri) {
        if (video.thumbnailUri == null) {
            thumbnailUri = VideoThumbnailHelper.getThumbnail(context, video.uri, video.id)
        }
    }

    val isNew = remember(video.dateAdded) {
        val secondsInDay = 24 * 60 * 60
        val diffSeconds = System.currentTimeMillis() / 1000 - video.dateAdded
        diffSeconds < secondsInDay
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            AsyncImage(
                model = thumbnailUri ?: video.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Duration badge bottom-right (mm:ss / h:mm:ss)
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = formatDuration(video.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // NEW badge top-left
            if (isNew) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(bottomEnd = 8.dp))
                        .background(Color(0xFFFF3B30))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "NEW",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // HDR badge top-right (golden tag)
            if (video.isHdr) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(bottomStart = 8.dp))
                        .background(Color(0xFFFFD54F))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "HDR",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        
        // title + folder name below thumbnail, single line, ellipsized
        Text(
            text = buildString {
                append(video.title)
                append(" • ")
                append(video.folderPath.substringAfterLast("/"))
            },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
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

@Composable
fun VideoListItem(
    video: Video,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailUri by remember(video.id) { mutableStateOf(video.thumbnailUri) }

    LaunchedEffect(video.id, video.thumbnailUri) {
        if (video.thumbnailUri == null) {
            thumbnailUri = VideoThumbnailHelper.getThumbnail(context, video.uri, video.id)
        }
    }

    val isNew = remember(video.dateAdded) {
        val secondsInDay = 24 * 60 * 60
        val diffSeconds = System.currentTimeMillis() / 1000 - video.dateAdded
        diffSeconds < secondsInDay
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            AsyncImage(
                model = thumbnailUri ?: video.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Duration
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                Text(
                    text = formatDuration(video.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // NEW badge top-left
            if (isNew) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(bottomEnd = 8.dp))
                        .background(Color(0xFFFF3B30))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "NEW",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // HDR badge top-right (golden tag)
            if (video.isHdr) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(bottomStart = 8.dp))
                        .background(Color(0xFFFFD54F))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "HDR",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(4.dp))

            val daysAgo = remember(video.dateAdded) {
                val diff = (System.currentTimeMillis() - (video.dateAdded * 1000)) / (1000 * 60 * 60 * 24)
                if (diff == 0L) "Today" else if (diff == 1L) "1 day ago" else "$diff days ago"
            }

            Text(
                text = "Added $daysAgo",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp
            )

            Spacer(Modifier.height(8.dp))

            // Tags row
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Resolution
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF34495E))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${video.resolutionHeight}p",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // File size
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF34495E))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatFileSize(video.sizeBytes),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Date added string
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF34495E))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatDateShort(video.dateAdded * 1000),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
