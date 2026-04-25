package com.beatflowy.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.theme.*

@Composable
fun NowPlayingSection(
    song: Song?,
    isPlaying: Boolean,
    progressMs: () -> Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Brush.verticalGradient(listOf(BgElevated, NowPlayingBg)))
            .clickable(onClick = onOpenFullPlayer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArtImage(song = song, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold
                    )
                    Text(
                        song.artist, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                        val currentIsPlaying = remember(isPlaying) { isPlaying }
                        Icon(
                            if (currentIsPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            null, tint = TextPrimary, modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Progress line at the bottom
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (durationMs > 0) (progressMs().toFloat() / durationMs) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                color = AccentBlue,
                trackColor = EqTrack
            )
        }
    }
}

@Composable
private fun TransportBtn(icon: ImageVector, onClick: () -> Unit, size: Dp, iconSize: Dp, tint: Color) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}
