package com.beatflowy.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun SongListItem(
    song: Song,
    isPlaying: Boolean,
    trackNumber: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false
) {
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isPlaying || isSelected) 1f else 0f,
        animationSpec = tween(300),
        label         = "rowBgAlpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                if (bgAlpha > 0f) {
                    val color = if (isSelected) AccentBlue.copy(alpha = 0.3f * bgAlpha) else AccentBlue.copy(alpha = 0.18f * bgAlpha)
                    drawRect(Brush.horizontalGradient(listOf(
                        color, Color.Transparent
                    )))
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
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
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    MiniEqBars()
                }
                Spacer(Modifier.width(12.dp))
            }
            AlbumArtImage(song = song, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                Text("${song.artist}-${song.album}", fontSize = 12.sp, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Badge, 
                        null, 
                        tint = TextMuted, 
                        modifier = Modifier.size(11.dp).padding(top = 1.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = buildString {
                            append(formatDuration(song.durationMs))
                            append(" | ${song.format.lowercase()}")
                            append(" | ${"%.1f".format(song.sampleRateHz / 1000.0)}kHz")
                            if (song.bitrate > 0) {
                                append(" | ${song.bitrate / 1000}kbps")
                            }
                            if (song.bitDepth > 0) {
                                append(" | ${song.bitDepth}bit")
                            }
                        },
                        fontSize = 11.sp,
                        color = TextMuted,
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
private fun MiniEqBars() {
    val inf = rememberInfiniteTransition(label = "eq")
    val b1 by inf.animateFloat(4f, 14f, infiniteRepeatable(tween(380), RepeatMode.Reverse), "b1")
    val b2 by inf.animateFloat(12f, 5f, infiniteRepeatable(tween(480), RepeatMode.Reverse), "b2")
    val b3 by inf.animateFloat(7f, 16f, infiniteRepeatable(tween(310), RepeatMode.Reverse), "b3")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom, modifier = Modifier.height(18.dp)) {
        listOf(b1, b2, b3).forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.5.dp))
                .background(AccentBlue))
        }
    }
}

private fun formatDuration(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}
