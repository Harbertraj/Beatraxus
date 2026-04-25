package com.beatflowy.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.theme.*
import java.util.concurrent.TimeUnit

@Composable
fun ListCardItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = BgDeep.copy(0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentBlue.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, color = TextMuted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun EmptyLibraryView() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.MusicNote, null,
            tint = TextMuted, modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No music found", style = MaterialTheme.typography.titleMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Try changing your library view or adding audio files",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun getFormatColor(format: String): Color {
    return when (format.lowercase()) {
        "flac" -> Color(0xFF00E5FF) // Cyan
        "wav" -> Color(0xFFFFD600)  // Gold/Yellow
        "alac", "m4a" -> Color(0xFFFF9100) // Orange
        "mp3" -> Color(0xFFB0BEC5)  // Blue Grey
        "aac" -> Color(0xFF00E676)  // Bright Green
        "ogg", "opus" -> Color(0xFFD1C4E9) // Lavender
        else -> Color.White.copy(0.6f)
    }
}

@Composable
fun SongListItem(
    song: Song,
    isPlaying: Boolean,
    trackNumber: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onMoreClick: (() -> Unit)? = null
) {
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isPlaying || isSelected) 1f else 0f,
        animationSpec = tween(300),
        label         = "rowBgAlpha"
    )

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .graphicsLayer {
                // Use graphicsLayer for clipping to improve scrolling performance
                clip = true
                shape = RoundedCornerShape(10.dp)
            }
            .drawBehind {
                if (bgAlpha > 0f) {
                    val color = if (isSelected) AccentBlue.copy(alpha = 0.3f * bgAlpha) else AccentBlue.copy(alpha = 0.18f * bgAlpha)
                    drawRect(Brush.horizontalGradient(listOf(
                        color, Color.Transparent
                    )))
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (onMoreClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMoreClick()
                    }
                }
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AccentBlue,
                        uncheckedColor = TextMuted,
                        checkmarkColor = Color.Black
                    )
                )
                Spacer(Modifier.width(8.dp))
            } else if (isPlaying) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    PlaybackBars()
                }
                Spacer(Modifier.width(10.dp))
            } else {
                Column(
                    modifier = Modifier.width(46.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val formatColor = getFormatColor(song.format)
                    Surface(
                        color = formatColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(5.dp),
                        border = BorderStroke(0.6.dp, formatColor.copy(alpha = 0.4f)),
                        modifier = Modifier.padding(bottom = 2.5.dp)
                    ) {
                        Text(
                            text = song.format.uppercase(),
                            color = formatColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Visible
                        )
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f))
                    ) {
                        Text(
                            text = formatDuration(song.durationMs),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            AlbumArtImage(
                song = song,
                size = 60.dp,
                modifier = Modifier.padding(2.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                Text("${song.artist} • ${song.album}", fontSize = 13.sp, color = Color.LightGray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Badge, 
                        null, 
                        tint = Color.Gray, 
                        modifier = Modifier.size(11.dp).padding(top = 1.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = buildString {
                            if (song.bitDepth > 0) {
                                append("${song.bitDepth}bit | ")
                            }
                            append("${"%.1f".format(song.sampleRateHz / 1000.0)}kHz")
                            if (song.bitrate > 0) {
                                append(" | ${song.bitrate / 1000}kbps")
                            }
                        },
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


@Composable
private fun PlaybackBars() {
    val inf = rememberInfiniteTransition(label = "playbackBars")
    val b1 by inf.animateFloat(0.3f, 0.9f, infiniteRepeatable(tween(380), RepeatMode.Reverse), "b1")
    val b2 by inf.animateFloat(0.8f, 0.4f, infiniteRepeatable(tween(480), RepeatMode.Reverse), "b2")
    val b3 by inf.animateFloat(0.5f, 1.0f, infiniteRepeatable(tween(310), RepeatMode.Reverse), "b3")
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp)
    ) {
        listOf(b1, b2, b3).forEach { scale ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(AccentBlue)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}
