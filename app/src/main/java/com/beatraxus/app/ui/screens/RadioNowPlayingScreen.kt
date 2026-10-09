package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.toSong
import com.beatraxus.app.repository.RadioStationCache

/** Accent shared with the Radio entry in the library drawer. */
private val RadioAccent = Color(0xFF00B8D4)

/**
 * Now Playing screen for live radio streams. It is shown instead of [NowPlayingScreen] whenever
 * the current song is a radio station (see `Song.isRadioStream`) and swaps back automatically as
 * soon as a normal song becomes current.
 *
 * Differences from the song screen: no audio-quality badge (a "RADIO" badge sits in its place),
 * no seek bar / time labels (a live stream has no duration), and previous / next step through
 * stations instead of tracks.
 */
@Composable
fun RadioNowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPlayStation: (Song) -> Unit,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnClose by rememberUpdatedState(onClose)
    val prevStation = RadioStationCache.neighbour(song.id, -1)
    val nextStation = RadioStationCache.neighbour(song.id, +1)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(RadioAccent.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.30f),
                        radius = size.width * 0.95f
                    )
                )
            }
            // Swallow touches so nothing underneath the full-screen player reacts to them.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > 160f) currentOnClose() },
                    onVerticalDrag = { _, dy -> dragged += dy }
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioTopButton(Icons.Rounded.KeyboardArrowDown, "Close", onClose)
            Text(
                text = "LIVE RADIO",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            RadioTopButton(Icons.Rounded.Tune, "Equalizer", onOpenEqualizer)
        }

        // Station logo with soft pulse rings while on air
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val artSize = minOf(maxWidth * 0.72f, maxHeight * 0.9f, 320.dp)
            RadioPulseRings(active = isPlaying, size = artSize)
            RadioStationLogo(
                logoUrl = song.albumArtUri?.toString(),
                accent = RadioAccent,
                size = artSize,
                cornerRadius = 32.dp,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
            )
        }

        Spacer(Modifier.height(20.dp))

        // "RADIO" badge — sits where the song screen shows the audio-quality badge
        RadioBadge(live = isPlaying)

        Spacer(Modifier.height(16.dp))

        Text(
            text = song.title.trim().ifBlank { "Radio" },
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.artist,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        // Live status in place of the seek bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioEqBars(color = RadioAccent, animate = isPlaying, barCount = 5, barWidth = 3.dp, maxHeight = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isPlaying) "Streaming live" else "Paused",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(28.dp))

        // Transport: previous station · play/pause · next station
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 36.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioStepButton(
                icon = Icons.Rounded.SkipPrevious,
                description = "Previous station",
                enabled = prevStation != null,
                onClick = { prevStation?.let { onPlayStation(it.toSong()) } }
            )
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(RadioAccent)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp)
                )
            }
            RadioStepButton(
                icon = Icons.Rounded.SkipNext,
                description = "Next station",
                enabled = nextStation != null,
                onClick = { nextStation?.let { onPlayStation(it.toSong()) } }
            )
        }
    }
}

@Composable
private fun RadioTopButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RadioStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.25f),
            modifier = Modifier.size(32.dp)
        )
    }
}

/** The "RADIO" pill that replaces the audio-quality badge on this screen. */
@Composable
private fun RadioBadge(live: Boolean) {
    val transition = rememberInfiniteTransition(label = "radioBadge")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "radioBadgeDot"
    )
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(RadioAccent.copy(alpha = 0.16f))
            .border(1.dp, RadioAccent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if (live) dotAlpha else 0.4f }
                .background(RadioAccent, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "RADIO",
            color = RadioAccent,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.5.sp
        )
    }
}

/** Two expanding, fading outlines behind the logo — a "broadcasting" cue, only while playing. */
@Composable
private fun RadioPulseRings(active: Boolean, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "radioRings")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "radioRingsPhase"
    )
    if (!active) return
    for (offset in listOf(0f, 0.5f)) {
        val p = (phase + offset) % 1f
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + 0.22f * p
                    scaleX = s
                    scaleY = s
                    alpha = 0.35f * (1f - p)
                }
                .border(2.dp, RadioAccent, RoundedCornerShape(32.dp))
        )
    }
}
