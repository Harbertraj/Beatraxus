package com.beatflowy.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
fun SongListItem(
    song: Song,
    isPlaying: Boolean,
    trackNumber: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isPlaying) 1f else 0f,
        animationSpec = tween(300),
        label         = "rowBgAlpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                if (bgAlpha > 0f) {
                    drawRect(Brush.horizontalGradient(listOf(
                        AccentBlue.copy(alpha = 0.18f * bgAlpha), Color.Transparent
                    )))
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isPlaying) MiniEqBars()
                else Text(trackNumber.toString(), fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, color = TextMuted)
            }
            Spacer(Modifier.width(12.dp))
            AlbumArtPlaceholder(size = 44, seed = song.id.hashCode())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 14.sp,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                    color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(song.artist, fontSize = 12.sp, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDuration(song.durationMs), fontSize = 12.sp,
                    color = TextMuted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                FormatBadge(format = song.format)
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

@Composable
fun AlbumArtPlaceholder(size: Int, seed: Int) {
    val hue    = (seed.and(0xFF) / 255f) * 360f
    val color1 = Color.hsv(hue, 0.7f, 0.5f)
    val color2 = Color.hsv((hue + 40f) % 360f, 0.6f, 0.7f)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))
        .background(Brush.linearGradient(listOf(color1, color2))))
}

@Composable
private fun FormatBadge(format: String) {
    val (bg, fg) = when (format.uppercase()) {
        "FLAC" -> AccentBlue.copy(alpha = 0.18f) to AccentBlueSoft
        "WAV"  -> AccentRed.copy(alpha = 0.15f)  to AccentRedSoft
        else   -> BgHighlight to TextSecondary
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(format.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
            color = fg, letterSpacing = 0.5.sp)
    }
}

private fun formatDuration(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}
