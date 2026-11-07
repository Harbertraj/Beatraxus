package com.beatraxus.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.ui.theme.*
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.beatraxus.app.ui.theme.Icon

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
    onMoreClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    isCompact: Boolean = false,
    isOnline: Boolean = true
) {
    val isAvailable = song.source == SongSource.LOCAL || isOnline
    
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isPlaying || isSelected) 1f else 0f,
        animationSpec = tween(300),
        label         = "rowBgAlpha"
    )

    val haptic = LocalHapticFeedback.current

    // Quality determination logic
    val songMetadata = remember(song) {
        val rawFormat = song.format.lowercase()
        val baseFormat = rawFormat.replace("g ", "").substringBefore(" ").trim()
        val bitDepth = when {
            rawFormat.contains("24") -> 24
            rawFormat.contains("16") -> 16
            song.bitDepth > 0 -> song.bitDepth
            else -> 16
        }
        val sampleRate = song.sampleRateHz
        val bitrate = song.bitrate

        val durationMin = song.durationMs / 60000.0
        val sizeMb = song.fileSizeBytes / (1024.0 * 1024.0)
        val isLikelyLossyM4A = (baseFormat == "m4a" || baseFormat == "mp4" || baseFormat == "aac") &&
                ((durationMin > 0 && (sizeMb / durationMin) < 2.3) || (bitrate > 0 && bitrate < 400000))

        val isALAC = baseFormat.contains("alac") || ((baseFormat == "m4a" || baseFormat == "mp4") && !isLikelyLossyM4A)
        val isLosslessFormat = baseFormat.contains("flac") || isALAC || baseFormat.contains("wav") || baseFormat.contains("dsd") || baseFormat.contains("aiff") || baseFormat.contains("dts") || baseFormat.contains("ac3")
        val isHiRes = (bitDepth >= 24 || sampleRate > 48000) && isLosslessFormat

        object {
            val baseFormat = baseFormat
            val bitDepth = bitDepth
            val sampleRate = sampleRate
            val bitrate = bitrate
            val isALAC = isALAC
            val isLosslessFormat = isLosslessFormat
            val isHiRes = isHiRes
        }
    }
    
    val baseFormat = songMetadata.baseFormat
    val bitDepth = songMetadata.bitDepth
    val sampleRate = songMetadata.sampleRate
    val bitrate = songMetadata.bitrate
    val isALAC = songMetadata.isALAC
    val isLosslessFormat = songMetadata.isLosslessFormat
    val isHiRes = songMetadata.isHiRes

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .graphicsLayer {
                // Use graphicsLayer for clipping to improve scrolling performance
                clip = true
                shape = RoundedCornerShape(10.dp)
                if (!isAvailable) {
                    alpha = 0.6f
                }
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
                onClick = { if (isAvailable || isMultiSelectMode) onClick() },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!isMultiSelectMode && onMoreClick != null) {
                        onMoreClick()
                    } else {
                        onLongClick?.invoke()
                    }
                }
            )
            .padding(horizontal = 12.dp, vertical = if (isCompact) 6.dp else 12.dp)
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
            }
            val artSize = if (isCompact) 44.dp else 60.dp
            Box(contentAlignment = Alignment.Center) {
                AlbumArtImage(
                    song = song,
                    size = artSize,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .graphicsLayer {
                            if (!isAvailable) {
                                alpha = 0.8f
                            }
                        },
                    grayscale = !isAvailable
                )
                // Now-playing indicator sits on the artwork, so rows never shift sideways when playback starts.
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlaybackBars()
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = if (isCompact) 14.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAvailable) Color.White else Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                val artistLine: @Composable (Modifier) -> Unit = { m ->
                    Text("${song.artist} • ${song.album}", fontSize = if (isCompact) 12.sp else 13.sp, color = if (isAvailable) Color.LightGray else Color.Gray.copy(0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                }
                if (isCompact) {
                    // Compact rows have no detail line, so the quality chip sits in front of the artist line.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QualityChip(baseFormat, isALAC, bitDepth, isHiRes, isAvailable)
                        Spacer(Modifier.width(6.dp))
                        artistLine(Modifier.weight(1f, fill = false))
                    }
                } else {
                    artistLine(Modifier)
                }
                
                if (!isCompact) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        val badgeColor = if (!isAvailable) Color.Gray else if (isHiRes) Color(0xFFFFD54F).copy(0.7f) else if (isLosslessFormat) Color(0xFF4FC3F7).copy(0.7f) else Color.Gray
                        QualityChip(baseFormat, isALAC, bitDepth, isHiRes, isAvailable)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = buildString {
                                append(formatDuration(song.durationMs))
                                
                                if (sampleRate > 0) {
                                    append(" | ")
                                    val khz = sampleRate / 1000.0
                                    if (khz == khz.toInt().toDouble()) {
                                        append("${khz.toInt()}kHz")
                                    } else {
                                        append("${"%.1f".format(java.util.Locale.US, khz)}kHz")
                                    }
                                }

                                if (song.source == SongSource.GDRIVE) {
                                    append(" | G DRIVE")
                                } else if (song.source == SongSource.DROPBOX) {
                                    append(" | DROPBOX")
                                } else if (song.source == SongSource.TELEGRAM) {
                                    append(" | TELEGRAM")
                                } else if (song.source == SongSource.WEB) {
                                    append(" | WEB")
                                } else {
                                    val accurateBitrate = if (bitrate > 0) {
                                        bitrate.toLong()
                                    } else if (song.durationMs > 0) {
                                        (song.fileSizeBytes * 8 * 1000) / song.durationMs
                                    } else 0L
                                    
                                    if (accurateBitrate > 0) {
                                        append(" | ${accurateBitrate / 1000}kbps")
                                    } else if (bitDepth > 0) {
                                        append(" | ${bitDepth}bit")
                                    }
                                }
                            },
                            fontSize = 11.sp,
                            color = badgeColor,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }


        }
    }
}


/**
 * One small inline quality chip, e.g. "WAV 16" or "FLAC 24". The chip is tinted by format; the bit depth is
 * gold for hi-res. Replaces the old two-badge column that took ~54dp of width away from the title.
 */
@Composable
private fun QualityChip(
    baseFormat: String,
    isALAC: Boolean,
    bitDepth: Int,
    isHiRes: Boolean,
    isAvailable: Boolean
) {
    val label = if (isALAC) "ALAC" else baseFormat.uppercase()
    val formatColor = if (isAvailable) getFormatColor(baseFormat) else Color.Gray
    val depthColor = if (!isAvailable) Color.Gray else if (isHiRes) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.85f)
    Surface(
        color = formatColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(0.6.dp, formatColor.copy(alpha = 0.4f)),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label, $bitDepth bit"
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = formatColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "$bitDepth",
                color = depthColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
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
