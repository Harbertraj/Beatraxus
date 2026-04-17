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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.theme.*

@Composable
fun NowPlayingSection(
    song: Song?,
    isPlaying: Boolean,
    progressMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Brush.verticalGradient(listOf(BgElevated, NowPlayingBg)))
            .padding(20.dp)
    ) {
        Column {
            // Drag handle
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape)
                .background(BgHighlight).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))

            if (song == null) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.MusicNote, null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Select a track to begin", color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RotatingAlbumArt(seed = song.id.hashCode(), isPlaying = isPlaying, size = 80.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(song.artist, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(song.album, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onOpenEqualizer,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(BgHighlight)) {
                        Icon(Icons.Rounded.Equalizer, "Equalizer",
                            tint = AccentBlue, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Progress
                val progress = if (durationMs > 0) (progressMs.toFloat() / durationMs) else 0f
                Slider(
                    value = progress,
                    onValueChange = { onSeek((it * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentBlue, activeTrackColor = AccentBlue,
                        inactiveTrackColor = EqTrack
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(progressMs), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(fmtTime(durationMs), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }

                Spacer(Modifier.height(20.dp))

                // Controls
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    TransportBtn(Icons.Rounded.SkipPrevious, onPrevious, 48.dp, 28.dp, TextSecondary)
                    Spacer(Modifier.width(24.dp))
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AccentBlue, GradientMid)))
                            .clickable(onClick = onPlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(isPlaying,
                            transitionSpec = { scaleIn(tween(150)) togetherWith scaleOut(tween(150)) },
                            label = "ppIcon") { playing ->
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                    TransportBtn(Icons.Rounded.SkipNext, onNext, 48.dp, 28.dp, TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun RotatingAlbumArt(seed: Int, isPlaying: Boolean, size: Dp) {
    val hue    = (seed.and(0xFF) / 255f) * 360f
    val c1     = Color.hsv(hue, 0.75f, 0.55f)
    val c2     = Color.hsv((hue + 60f) % 360f, 0.65f, 0.75f)
    val c3     = Color.hsv((hue + 120f) % 360f, 0.7f, 0.65f)
    Box(
        Modifier.size(size).shadow(8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(c1, c2, c3))),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(size * 0.4f).clip(CircleShape).background(BgDeep.copy(alpha = 0.7f)))
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
