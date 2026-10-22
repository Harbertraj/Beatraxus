package com.beatraxus.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import com.beatraxus.app.ui.theme.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.Song
import com.beatraxus.app.utils.DecadeBucket
import com.beatraxus.app.utils.GenreBucket
import com.beatraxus.app.utils.hiResBadge

// Extra audio-home sections that live under "Browse by Mood". The song selection logic is in
// utils/HomeSectionLogic.kt (pure + unit-tested); this file is only the presentation.

private val DecadePalette = listOf(
    Color(0xFFC9A66B), // 50s and earlier
    Color(0xFFFF8A3D), // 60s / 70s
    Color(0xFFFF4D9D), // 80s
    Color(0xFF8E6BFF), // 90s
    Color(0xFF00C2D1), // 2000s
    Color(0xFF32D74B), // 2010s
    Color(0xFF0A84FF)  // 2020s
)

private val GenrePalette = listOf(
    AudioViolet, AudioPink, AudioCyan, AudioAmber, Color(0xFF32D74B), Color(0xFFFF6B00), Color(0xFF5E9BFF)
)

private fun decadeColor(startYear: Int): Color =
    DecadePalette[((startYear - 1950) / 10).coerceIn(0, DecadePalette.lastIndex)]

// ───────────────────────────── Section builders ─────────────────────────────

/** Hi-res tracks with their bit-depth / sample-rate badge. */
internal fun LazyListScope.hiResVaultSection(songs: List<Song>, onPlayAt: (Int) -> Unit) {
    if (songs.isEmpty()) return
    item(key = "HI_RES_VAULT") {
        HomeSectionHeader(
            title = "Hi-Res Vault",
            actionText = "Play All",
            actionIcon = Icons.Rounded.PlayArrow,
            onActionClick = { onPlayAt(0) }
        )
    }
    item(key = "HI_RES_VAULT_CONTENT") {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(songs, key = { _, s -> "hires_${s.id}" }) { index, song ->
                HiResVaultCard(song) { onPlayAt(index) }
            }
        }
    }
}

/** One tile per decade found in the library; tapping shuffles that decade. */
internal fun LazyListScope.decadesSection(buckets: List<DecadeBucket<Song>>, onPlay: (List<Song>) -> Unit) {
    if (buckets.isEmpty()) return
    item(key = "DECADES") { HomeSectionHeader("Through the Decades") }
    item(key = "DECADES_CONTENT") {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(buckets, key = { it.startYear }) { bucket ->
                DecadeTile(
                    label = bucket.label,
                    count = bucket.items.size,
                    color = decadeColor(bucket.startYear)
                ) { onPlay(bucket.items.shuffled()) }
            }
        }
    }
}

/** Your most common genres, each with a collage of its album art. */
internal fun LazyListScope.genresSection(buckets: List<GenreBucket<Song>>, onPlay: (List<Song>) -> Unit) {
    if (buckets.isEmpty()) return
    item(key = "GENRES") { HomeSectionHeader("Dive into a Genre") }
    item(key = "GENRES_CONTENT") {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(buckets, key = { _, b -> b.name }) { index, bucket ->
                GenreCard(
                    name = bucket.name,
                    count = bucket.items.size,
                    covers = bucket.items.mapNotNull { it.albumArtUri }.distinct().take(3),
                    accent = GenrePalette[index % GenrePalette.size]
                ) { onPlay(bucket.items.shuffled()) }
            }
        }
    }
}

/** Songs you haven't played lately, reshuffled once a day. */
internal fun LazyListScope.rediscoverSection(songs: List<Song>, onPlayAt: (Int) -> Unit) {
    if (songs.isEmpty()) return
    item(key = "REDISCOVER") {
        HomeSectionHeader(
            title = "Rediscover",
            actionText = "Shuffle",
            actionIcon = Icons.Rounded.Shuffle,
            onActionClick = { onPlayAt(0) }
        )
    }
    item(key = "REDISCOVER_CONTENT") {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(songs, key = { _, s -> "rediscover_${s.id}" }) { index, song ->
                HomeSongItem(song) { onPlayAt(index) }
            }
        }
    }
}

// ───────────────────────────────── Cards ────────────────────────────────────

@Composable
private fun HiResVaultCard(song: Song, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .width(156.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(156.dp)
                .clip(shape)
                .background(Color.White.copy(0.05f))
                .border(
                    BorderStroke(1.dp, Brush.verticalGradient(listOf(AudioGold.copy(0.7f), AudioGold.copy(0.08f)))),
                    shape
                )
        ) {
            if (song.albumArtUri != null) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                DefaultAlbumArt(modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(0.6f)))
            )
            val badge = hiResBadge(song.bitDepth, song.sampleRateHz)
            if (badge.isNotEmpty()) {
                Text(
                    text = badge,
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(AudioGold, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .background(Color.Black.copy(0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = listOf(song.artist, song.format.uppercase()).filter { it.isNotBlank() }.joinToString(" · "),
            color = Color.White.copy(0.6f),
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun DecadeTile(label: String, count: Int, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 156.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(0.70f), color.copy(0.12f))))
            .border(
                0.8.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.30f), color.copy(0.25f))),
                shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp)
    ) {
        // Vinyl-record rings peeking out of the corner
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 34.dp, y = 34.dp)
        ) {
            drawCircle(color = Color.White.copy(0.10f), style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.White.copy(0.07f), radius = size.minDimension * 0.32f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.White.copy(0.14f), radius = size.minDimension * 0.08f)
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = if (label.length > 3) 28.sp else 36.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Column(modifier = Modifier.align(Alignment.BottomStart)) {
            Text(
                text = if (count == 1) "1 song" else "$count songs",
                color = Color.White.copy(0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(0.28f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("PLAY", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun GenreCard(name: String, count: Int, covers: List<Uri>, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val tileShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .width(176.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(0.30f), accent.copy(0.06f))))
            .border(
                0.8.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.25f), accent.copy(0.30f))),
                shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(72.dp)) {
            if (covers.isEmpty()) {
                Box(
                    modifier = Modifier.size(72.dp).clip(tileShape).background(accent.copy(0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = accent, modifier = Modifier.size(30.dp))
                }
            }
            // Overlapping collage: each cover sits partly over the previous one
            covers.forEachIndexed { i, uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = (i * 34).dp)
                        .size(72.dp)
                        .clip(tileShape)
                        .border(1.5.dp, Color.Black.copy(0.4f), tileShape)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (count == 1) "1 song" else "$count songs",
            color = Color.White.copy(0.65f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
