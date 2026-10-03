package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.model.Song
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Audio-mode home palette (video mode keeps its cyan/blue "cinema" palette)
// ─────────────────────────────────────────────────────────────────────────────
internal val AudioViolet = Color(0xFF8E6BFF)
internal val AudioPink = Color(0xFFFF4D9D)
internal val AudioCyan = Color(0xFF00F2FF)
internal val AudioAmber = Color(0xFFFFB84D)
internal val AudioGold = Color(0xFFFFD54F)
private val AudioInk = Color(0xFF0A0A12)

/** "FLAC · 24-bit / 96 kHz" style label for a song, or null when nothing is known. */
internal fun audioQualityLabel(song: Song): String? {
    val parts = mutableListOf<String>()
    if (song.format.isNotBlank()) parts += song.format.uppercase(Locale.US)
    val bits = if (song.bitDepth > 0) "${song.bitDepth}-bit" else null
    val khz = if (song.sampleRateHz > 0) {
        if (song.sampleRateHz % 1000 == 0) "${song.sampleRateHz / 1000} kHz"
        else String.format(Locale.US, "%.1f kHz", song.sampleRateHz / 1000f)
    } else null
    val tech = listOfNotNull(bits, khz).joinToString(" / ")
    if (tech.isNotEmpty()) parts += tech
    return parts.joinToString(" · ").ifEmpty { null }
}

internal fun isHiResSong(song: Song): Boolean = song.bitDepth >= 24 || song.sampleRateHz >= 88200

private fun formatTotalDuration(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ─────────────────────────────────────────────────────────────────────────────
// Greeting header
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioGreetingHeader(
    greeting: String,
    greetingIcon: ImageVector,
    accent: Color,
    name: String,
    songCount: Int,
    favoriteCount: Int,
    albumCount: Int,
    artistCount: Int,
    totalDurationMs: Long
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(accent.copy(alpha = 0.16f), CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(greetingIcon, null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = greeting.uppercase(Locale.US),
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioStatPill(Icons.Rounded.MusicNote, "$songCount songs", AudioPink)
            AudioStatPill(Icons.Rounded.Favorite, "$favoriteCount liked", Color(0xFFFF5E62))
            AudioStatPill(Icons.Rounded.Album, "$albumCount albums", AudioViolet)
            AudioStatPill(Icons.Rounded.Person, "$artistCount artists", AudioCyan)
            if (totalDurationMs > 0) {
                AudioStatPill(Icons.Rounded.Schedule, formatTotalDuration(totalDurationMs), AudioAmber)
            }
        }
    }
}

@Composable
private fun AudioStatPill(icon: ImageVector, text: String, tint: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.10f))
            .border(0.5.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero card with spinning vinyl
//
// Background follows Settings > Appearance > Mini Player Background (same style as the
// Now Playing bar), so the card and the bar always match.
//
// Layout (fixed rows, so a HI-RES badge or a 2-line title can never push the play button):
//   top    : status chip  [+ HI-RES chip]
//   middle : title / artist / quality text   (takes the remaining space)
//   bottom : play/pause button + equalizer bars
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioHeroCard(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    hasHistory: Boolean,
    onPlay: () -> Unit,
    appearance: AppearanceConfig = AppearanceConfig(),
    dominantColor: Color = AudioViolet
) {
    val playingNow = isCurrent && isPlaying
    val label = when {
        playingNow -> "NOW PLAYING"
        isCurrent -> "PAUSED"
        hasHistory -> "JUMP BACK IN"
        else -> "START LISTENING"
    }

    // The record only spins while music is playing and keeps its angle when paused.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playingNow) {
        if (playingNow) {
            while (true) {
                rotation.snapTo(rotation.value % 360f)
                rotation.animateTo(rotation.value + 360f, tween(9000, easing = LinearEasing))
            }
        }
    }

    val shape = RoundedCornerShape(30.dp)
    val quality = remember(song) { audioQualityLabel(song) }
    val hiRes = remember(song) { isHiResSong(song) }
    val accent = if (isCurrent) dominantColor else AudioViolet

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(16.dp, shape, ambientColor = AudioViolet, spotColor = AudioPink)
            .clip(shape)
            .height(212.dp)
            .background(AudioInk)
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(0.28f), Color.White.copy(0.04f)))
                ),
                shape
            )
            .clickable(onClick = onPlay)
    ) {
        HeroBackground(song = song, appearance = appearance, accent = accent)

        // very light left-side readability tint (no heavy dark blotch behind the text any more)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(0.28f),
                        0.65f to Color.Transparent
                    )
                )
        )

        // soft coloured glow behind the record
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val c = Offset(size.width * 0.82f, size.height * 0.5f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(AudioPink.copy(0.26f), AudioViolet.copy(0.10f), Color.Transparent),
                            center = c,
                            radius = size.height * 0.95f
                        ),
                        radius = size.height * 0.95f,
                        center = c
                    )
                }
        )

        // Vinyl record, partly slid out of the card on the right
        VinylDisc(
            artModel = song.albumArtUri,
            rotationDeg = rotation.value,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 46.dp)
                .size(180.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 20.dp, end = 128.dp, top = 16.dp, bottom = 16.dp)
        ) {
            // ── top: status chip (+ HI-RES) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(0.35f))
                        .border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).background(if (playingNow) AudioPink else AudioCyan, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(label, color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp, maxLines = 1)
                }
                if (hiRes) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "HI-RES",
                        color = Color.Black,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AudioGold)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            // ── middle: title / artist / quality ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    color = Color.White.copy(0.72f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (quality != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = quality,
                        color = if (hiRes) AudioGold.copy(alpha = 0.9f) else Color.White.copy(0.55f),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ── bottom: play / pause + equalizer ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .shadow(10.dp, CircleShape, ambientColor = AudioPink, spotColor = AudioPink)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(AudioPink, AudioViolet)))
                        .clickable(role = Role.Button, onClick = onPlay)
                        .semantics {
                            contentDescription = if (playingNow) "Pause" else "Play"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playingNow) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                EqBars(active = playingNow, color = AudioCyan)
            }
        }
    }
}

/** Card background driven by the Mini Player Background appearance settings. */
@Composable
private fun HeroBackground(song: Song, appearance: AppearanceConfig, accent: Color) {
    Box(Modifier.fillMaxSize()) {
        when (appearance.miniPlayerBackgroundMode) {
            NowPlayingBackgroundMode.BLACK -> {
                Box(Modifier.fillMaxSize().background(AudioInk))
            }

            NowPlayingBackgroundMode.SOLID -> {
                Box(Modifier.fillMaxSize().background(accent.copy(alpha = appearance.miniPlayerSolidColorIntensity.coerceIn(0f, 1f))))
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = appearance.miniPlayerSolidColorDarkness.coerceIn(0f, 1f))))
            }

            NowPlayingBackgroundMode.BLUR -> {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.3f
                                scaleY = 1.3f
                            }
                            .blur(appearance.miniPlayerBlurIntensity.coerceIn(0f, 100f).dp),
                        onError = { }
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(AudioViolet.copy(0.55f), AudioPink.copy(0.35f), AudioInk)
                                )
                            )
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = appearance.miniPlayerBlurDarkness.coerceIn(0f, 1f))))
            }
        }
    }
}

@Composable
private fun EqBars(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "eqBars")
    val durations = listOf(420, 560, 380, 640, 500)
    Row(
        modifier = Modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        durations.forEachIndexed { i, ms ->
            val level by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "eq$i"
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (active) level else 0.3f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = if (active) 1f else 0.5f))
            )
        }
    }
}

@Composable
private fun VinylDisc(
    artModel: Any?,
    rotationDeg: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // body + grooves + label (these rotate)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotationDeg },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF2B2B35), Color(0xFF0B0B10)),
                        center = center,
                        radius = r
                    ),
                    radius = r
                )
                var rr = r * 0.97f
                while (rr > r * 0.42f) {
                    drawCircle(Color.White.copy(alpha = 0.05f), radius = rr, style = Stroke(width = 0.8f))
                    rr -= r * 0.045f
                }
                drawCircle(Color.White.copy(alpha = 0.16f), radius = r, style = Stroke(width = 1.5f))
            }
            Box(
                Modifier
                    .fillMaxSize(0.40f)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AudioViolet, AudioPink)))
            ) {
                if (artModel != null) {
                    AsyncImage(
                        model = artModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = { }
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote, null,
                        tint = Color.White.copy(0.9f),
                        modifier = Modifier.align(Alignment.Center).size(26.dp)
                    )
                }
            }
            Box(Modifier.size(9.dp).background(AudioInk, CircleShape))
        }
        // light sheen that stays still while the record turns
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = center
                ),
                radius = r
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quick actions (Shuffle All / Favorites / Recently Added)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioQuickActions(
    onShuffleAll: () -> Unit,
    onFavorites: () -> Unit,
    onRecentlyAdded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AudioQuickTile("Shuffle All", Icons.Rounded.Shuffle, AudioPink, Modifier.weight(1f), onShuffleAll)
        AudioQuickTile("Favorites", Icons.Rounded.Favorite, Color(0xFFFF5E62), Modifier.weight(1f), onFavorites)
        AudioQuickTile("Recently Added", Icons.Rounded.NewReleases, AudioAmber, Modifier.weight(1f), onRecentlyAdded)
    }
}

@Composable
private fun AudioQuickTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.06f))))
            .border(0.5.dp, accent.copy(alpha = 0.35f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(accent.copy(alpha = 0.22f), CircleShape)
                .border(0.5.dp, accent.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Featured ("Made For You") card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioFeaturedCard(
    song: Song,
    rank: Int,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .width(292.dp)
            .height(168.dp)
            .clip(shape)
            .background(AudioInk)
            .border(
                BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.22f), Color.White.copy(0.04f)))),
                shape
            )
            .clickable(onClick = onClick)
    ) {
        if (song.albumArtUri != null) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { }
            )
        } else {
            DefaultAlbumArt(modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(0.30f),
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(0.90f)
                    )
                )
        )

        // rank + label
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(0.45f))
                .border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                String.format(Locale.US, "%02d", rank),
                color = AudioPink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "FEATURED",
                color = AudioCyan,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.6.sp
            )
        }

        if (isHiResSong(song)) {
            Text(
                "HI-RES",
                color = Color.Black,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .background(AudioGold, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 76.dp, bottom = 14.dp)
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = Color.White.copy(0.72f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 14.dp)
                .size(46.dp)
                .shadow(10.dp, CircleShape, ambientColor = AudioPink, spotColor = AudioPink)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(AudioPink, AudioViolet))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
