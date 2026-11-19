package com.beatraxus.app.ui.vinyl

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.LibraryView
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.theme.Icon
import com.beatraxus.app.utils.DecadeBucket
import java.util.Locale

/*
 * "Vinyl Lounge" layout.
 *
 * The theme file (assets/themes/vinyl-lounge.json) restyles the app through the normal theme settings: a warm
 * espresso palette, a serif font, a wavy-glass seekbar, blurred album-art backgrounds, very round corners, a pill
 * mini player with a progress ring and a golden quality badge. This file adds the Home screen with its own
 * structure (a spinning record instead of cards and tables), shown only while the theme "vinyl-lounge" is active.
 * Other themes, including Graphite Pro, keep their own Home.
 *
 * Colours come from MaterialTheme, so the layout follows the theme and the user's later tweaks.
 * Every action of the other Home layouts is kept.
 */

const val VINYL_THEME_ID = "vinyl-lounge"

/** True while the built-in "Vinyl Lounge" theme is applied. */
val AppearanceConfig.isVinyl: Boolean get() = activeTheme?.id == VINYL_THEME_ID

private val vinylMoods = listOf("Calm", "Focus", "Workout", "Romantic", "Late Night", "Road Trip")

private fun khz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000f)

private fun isHiRes(song: Song): Boolean = song.bitDepth >= 24 || song.sampleRateHz >= 88200

private fun qualityText(song: Song): String {
    val fmt = song.format.substringAfterLast('/').trim().uppercase(Locale.US).ifEmpty { "AUDIO" }
    return if (isHiRes(song)) "$fmt ${song.bitDepth}/${khz(song.sampleRateHz)}" else fmt
}

// ------------------------------------------------------------------ shared pieces

/** Tonal tile with artwork on top; the first letter shows while there is no artwork. */
@Composable
private fun VinylCover(art: Uri?, shape: Shape, modifier: Modifier, letter: String? = null, letterSize: Int = 22) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        if (letter != null) {
            Text(letter, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = letterSize.sp, fontWeight = FontWeight.Bold)
        }
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Section title: serif heading with a small accent "side" label above it. */
@Composable
private fun VinylSection(side: String, title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                side.uppercase(Locale.getDefault()), color = MaterialTheme.colorScheme.primary,
                fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (action != null) {
            Text(
                action, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** A record: black vinyl with grooves, a sheen, the artwork as the centre label and a spindle hole. */
@Composable
private fun VinylDisc(song: Song, angle: Float, size: Dp) {
    val hole = MaterialTheme.colorScheme.background
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(angle)) {
            val r = this.size.minDimension / 2f
            drawCircle(Color(0xFF0C0806), radius = r)
            for (i in 0..9) {
                drawCircle(Color.White.copy(alpha = 0.05f), radius = r * (0.42f + i * 0.055f), style = Stroke(width = 1f))
            }
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent)
                ),
                radius = r
            )
        }
        VinylCover(
            song.albumArtUri, CircleShape, Modifier.size(size * 0.36f).rotate(angle),
            letter = song.title.trim().take(1).uppercase(Locale.getDefault()), letterSize = 30
        )
        Box(Modifier.size(10.dp).clip(CircleShape).background(hole))
    }
}

@Composable
private fun VinylNow(
    song: Song, isCurrent: Boolean, isPlaying: Boolean, hasHistory: Boolean, onPlay: () -> Unit
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isCurrent && isPlaying) {
        if (isCurrent && isPlaying) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                angle = (angle + (now - last) / 1_000_000_000f * 40f) % 360f
                last = now
            }
        }
    }
    val eyebrow = when {
        isCurrent -> "Now spinning"
        hasHistory -> "Pick up where you left off"
        else -> "Suggested for tonight"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VinylDisc(song, angle, 250.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            eyebrow.uppercase(Locale.getDefault()), color = MaterialTheme.colorScheme.primary,
            fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Text(
            song.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(
            song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tagColor = if (isHiRes(song)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                qualityText(song), color = tagColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(CircleShape).border(1.dp, tagColor.copy(alpha = 0.6f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isCurrent && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isCurrent && isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

@Composable
private fun VinylPill(label: String, onClick: () -> Unit) {
    Text(
        label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

/** Round icon button with its label underneath. */
@Composable
private fun VinylAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VinylSleeve(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        VinylCover(
            song.albumArtUri, MaterialTheme.shapes.large, Modifier.size(140.dp),
            letter = song.title.trim().take(1).uppercase(Locale.getDefault()), letterSize = 38
        )
        Spacer(Modifier.height(8.dp))
        Text(song.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ home

/**
 * Audio Home for the Vinyl Lounge theme. Same data and actions as the other Home layouts: library counts,
 * continue listening, quick actions, mixes, listen again, moods, Hi-Res Vault, artists and decades.
 */
fun LazyListScope.vinylHomeItems(
    greeting: String,
    songCount: Int,
    favoriteCount: Int,
    albumCount: Int,
    heroSong: Song?,
    heroIsCurrent: Boolean,
    heroIsPlaying: Boolean,
    quickPicks: List<Song>,
    recentlyPlayed: List<Song>,
    hiResSongs: List<Song>,
    rediscoverSongs: List<Song>,
    artists: List<Triple<String, String, Uri?>>,
    decades: List<DecadeBucket<Song>>,
    onNavigate: (LibraryView) -> Unit,
    onPlayHero: () -> Unit,
    onLiked: () -> Unit,
    onShuffleAll: () -> Unit,
    onRecentlyAdded: () -> Unit,
    onHiResVault: () -> Unit,
    onRediscover: () -> Unit,
    onPlayList: (List<Song>, Int) -> Unit,
    onMood: (String) -> Unit,
    onArtist: (String) -> Unit,
) {
    item(key = "vinyl_header") {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            Text(
                greeting, color = MaterialTheme.colorScheme.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "%,d songs · %,d albums · %,d artists · %,d liked".format(Locale.getDefault(), songCount, albumCount, artists.size, favoriteCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 2
            )
        }
    }

    item(key = "vinyl_nav") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 18.dp)
        ) {
            item { VinylPill("Songs") { onNavigate(LibraryView.ALL_SONGS) } }
            item { VinylPill("Albums") { onNavigate(LibraryView.ALBUMS) } }
            item { VinylPill("Artists") { onNavigate(LibraryView.ARTISTS) } }
            item { VinylPill("Folders") { onNavigate(LibraryView.FOLDERS) } }
            item { VinylPill("Playlists") { onNavigate(LibraryView.PLAYLISTS) } }
            item { VinylPill("Years") { onNavigate(LibraryView.YEARS) } }
            item { VinylPill("Genres") { onNavigate(LibraryView.GENRES) } }
            item { VinylPill("Favorites") { onNavigate(LibraryView.FAVORITES) } }
            item { VinylPill("Cloud") { onNavigate(LibraryView.CLOUD) } }
        }
    }

    if (heroSong != null) {
        item(key = "vinyl_hero") {
            Column {
                Spacer(Modifier.height(28.dp))
                VinylNow(heroSong, heroIsCurrent, heroIsPlaying, recentlyPlayed.isNotEmpty(), onPlayHero)
            }
        }
    }

    item(key = "vinyl_actions") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 28.dp)
        ) {
            item { VinylAction(Icons.Rounded.Favorite, "Liked Songs", onLiked) }
            item { VinylAction(Icons.Rounded.Shuffle, "Shuffle All", onShuffleAll) }
            item { VinylAction(Icons.Rounded.AccessTime, "Recently Added", onRecentlyAdded) }
            item { VinylAction(Icons.Rounded.Diamond, "Hi-Res Vault", onHiResVault) }
            item { VinylAction(Icons.Rounded.Refresh, "Rediscover", onRediscover) }
            item { VinylAction(Icons.Rounded.Cloud, "Cloud Library") { onNavigate(LibraryView.CLOUD) } }
        }
    }

    val mixes = buildList {
        if (quickPicks.isNotEmpty()) add(Triple("Quick picks", "Fresh selections for right now", quickPicks))
        if (rediscoverSongs.isNotEmpty()) add(Triple("Rediscover", "Songs you have not played lately", rediscoverSongs))
        if (hiResSongs.isNotEmpty()) add(Triple("Hi-Res mix", "24-bit and up", hiResSongs))
    }
    if (mixes.isNotEmpty()) {
        item(key = "vinyl_mix_title") { VinylSection("Side A", "Made for you") }
        item(key = "vinyl_mix") {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(mixes.size) { i ->
                    val (title, sub, list) = mixes[i]
                    val tint = if (i % 2 == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    Column(
                        Modifier
                            .width(220.dp)
                            .height(124.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.34f), MaterialTheme.colorScheme.surface)))
                            .clickable { onPlayList(list, 0) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = tint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${list.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }

    if (recentlyPlayed.isNotEmpty()) {
        item(key = "vinyl_again_title") {
            VinylSection("Side B", "Spin it again", "View all") { onNavigate(LibraryView.RECENTLY_PLAYED) }
        }
        item(key = "vinyl_again") {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(recentlyPlayed.size) { i -> VinylSleeve(recentlyPlayed[i]) { onPlayList(recentlyPlayed, i) } }
            }
        }
    }

    item(key = "vinyl_mood_title") { VinylSection("Set the scene", "Browse by mood", "See all") { onMood("") } }
    item(key = "vinyl_mood") {
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vinylMoods.size) { i ->
                val label = vinylMoods[i]
                val tint = if (i % 2 == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                Row(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable { onMood(label) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }

    if (hiResSongs.isNotEmpty()) {
        item(key = "vinyl_hires_title") { VinylSection("Audiophile shelf", "Hi-Res Vault", "Play all", onHiResVault) }
        val shown = hiResSongs.take(4)
        items(shown.size, key = { "vinyl_hires_${shown[it].id}" }) { i ->
            val song = shown[i]
            Row(
                Modifier.fillMaxWidth().clickable { onPlayList(hiResSongs, i) }.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "%02d".format(i + 1), color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp), maxLines = 1, softWrap = false
                )
                VinylCover(
                    song.albumArtUri, MaterialTheme.shapes.small, Modifier.size(50.dp),
                    letter = song.title.trim().take(1).uppercase(Locale.getDefault())
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${song.bitDepth}/${khz(song.sampleRateHz)}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }

    if (artists.isNotEmpty()) {
        item(key = "vinyl_artists_title") { VinylSection("The band", "Artists", "View all") { onNavigate(LibraryView.ARTISTS) } }
        item(key = "vinyl_artists") {
            val top = artists.take(10)
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(top.size) { i ->
                    val artist = top[i]
                    Column(Modifier.width(96.dp).clickable { onArtist(artist.first) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        VinylCover(
                            artist.third, CircleShape,
                            Modifier.size(96.dp).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape).padding(4.dp).clip(CircleShape),
                            letter = artist.first.trim().take(1).uppercase(Locale.getDefault()), letterSize = 34
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            artist.first, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (decades.isNotEmpty()) {
        item(key = "vinyl_decades_title") { VinylSection("Crate digging", "Through the decades") }
        item(key = "vinyl_decades") {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(decades.size) { i ->
                    val bucket = decades[i]
                    Box(
                        Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                            .clickable { onPlayList(bucket.items, 0) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(bucket.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            Text("${bucket.items.size} tracks", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
