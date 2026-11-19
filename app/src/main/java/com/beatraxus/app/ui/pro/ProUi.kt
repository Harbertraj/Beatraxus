package com.beatraxus.app.ui.pro

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * "Graphite Pro" layout.
 *
 * The theme file (assets/themes/graphite-pro.json) restyles the whole app through the normal theme settings:
 * flat black backgrounds, graphite panels, a brass accent, tight corners, a hairline quality badge, a classic
 * mini player, a thin seekbar and soft icons. This file adds the one screen that needs its own structure, the
 * Home screen, shown only while the theme with the id "graphite-pro" is active (see [isPro]). Every other theme
 * keeps the existing Home untouched.
 *
 * Nothing here is hard-coded to a colour: panels, lines, text and accents come from MaterialTheme, so they follow
 * whatever the theme (and the user's later tweaks) say. Every action the other Home screens offer is still here.
 */

const val PRO_THEME_ID = "graphite-pro"

/** True while the built-in "Graphite Pro" theme is applied. */
val AppearanceConfig.isPro: Boolean get() = activeTheme?.id == PRO_THEME_ID

/** Moods on the Home screen. The labels match the ones the main screen resolves (unknown label = open the full list). */
private val proMoods = listOf("Calm", "Focus", "Workout", "Romantic", "Late Night", "Road Trip")

private fun khz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000f)

private fun isHiRes(song: Song): Boolean = song.bitDepth >= 24 || song.sampleRateHz >= 88200

/** "FLAC 24/96" for hi-res files, otherwise just the format ("FLAC", "MP3"). */
private fun qualityText(song: Song): String {
    val fmt = song.format.substringAfterLast('/').trim().uppercase(Locale.US).ifEmpty { "AUDIO" }
    return if (isHiRes(song)) "$fmt ${song.bitDepth}/${khz(song.sampleRateHz)}" else fmt
}

// ------------------------------------------------------------------ shared pieces

@Composable
private fun lineColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

/** Flat tonal square with the artwork on top; the first letter shows while there is no artwork. */
@Composable
private fun ProCover(art: Uri?, shape: Shape, modifier: Modifier, letter: String? = null, letterSize: Int = 20) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (letter != null) {
            Text(
                letter,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = letterSize.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Hairline-outlined pill, used for the quality tag. */
@Composable
private fun ProTag(text: String, highlight: Boolean = false) {
    val color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.55f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** Small uppercase heading with a hairline running to the optional action on the right. */
@Composable
private fun ProSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(Locale.getDefault()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            maxLines = 1
        )
        Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(1.dp).background(lineColor()))
        if (action != null) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

@Composable
private fun ProStat(value: Int, label: String, modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "%,d".format(Locale.getDefault(), value),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label.uppercase(Locale.getDefault()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ProHeader(greeting: String, songCount: Int, albumCount: Int, artistCount: Int, favoriteCount: Int) {
    val shape = MaterialTheme.shapes.medium
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
            Spacer(Modifier.width(8.dp))
            Text(
                greeting.uppercase(Locale.getDefault()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Your library",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, lineColor(), shape)
        ) {
            ProStat(songCount, "Songs", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor()))
            ProStat(albumCount, "Albums", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor()))
            ProStat(artistCount, "Artists", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor()))
            ProStat(favoriteCount, "Liked", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProChip(label: String, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .border(1.dp, lineColor(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The "continue listening" card: artwork, title, quality tag and one accent play/pause button. */
@Composable
private fun ProNowCard(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    hasHistory: Boolean,
    onPlay: () -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    val eyebrow = when {
        isCurrent -> "Now playing"
        hasHistory -> "Continue listening"
        else -> "Suggested"
    }
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, lineColor(), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProCover(
            song.albumArtUri, MaterialTheme.shapes.small, Modifier.size(76.dp),
            letter = song.title.trim().take(1).uppercase(Locale.getDefault()), letterSize = 26
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                eyebrow.uppercase(Locale.getDefault()),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                song.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            ProTag(qualityText(song), highlight = isHiRes(song))
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(46.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isCurrent && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isCurrent && isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun ProAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, lineColor(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** One row of a list section: leading tile, two lines of text, trailing detail. */
@Composable
private fun ProRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp).height(1.dp).background(lineColor()))
    }
}

@Composable
private fun SquareSong(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = onClick)) {
        ProCover(
            song.albumArtUri, MaterialTheme.shapes.small, Modifier.size(128.dp),
            letter = song.title.trim().take(1).uppercase(Locale.getDefault()), letterSize = 34
        )
        Spacer(Modifier.height(8.dp))
        Text(
            song.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ------------------------------------------------------------------ home

/**
 * Audio Home for the Graphite Pro theme. Same data and actions as the other Home layouts: library stats,
 * continue listening, quick actions, mixes, listen again, moods, Hi-Res Vault, artists and decades.
 */
fun LazyListScope.proHomeItems(
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
    item(key = "pro_header") {
        ProHeader(greeting, songCount, albumCount, artists.size, favoriteCount)
    }

    item(key = "pro_nav") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            item { ProChip("Songs") { onNavigate(LibraryView.ALL_SONGS) } }
            item { ProChip("Albums") { onNavigate(LibraryView.ALBUMS) } }
            item { ProChip("Artists") { onNavigate(LibraryView.ARTISTS) } }
            item { ProChip("Folders") { onNavigate(LibraryView.FOLDERS) } }
            item { ProChip("Playlists") { onNavigate(LibraryView.PLAYLISTS) } }
            item { ProChip("Years") { onNavigate(LibraryView.YEARS) } }
            item { ProChip("Genres") { onNavigate(LibraryView.GENRES) } }
            item { ProChip("Favorites") { onNavigate(LibraryView.FAVORITES) } }
            item { ProChip("Cloud") { onNavigate(LibraryView.CLOUD) } }
        }
    }

    if (heroSong != null) {
        item(key = "pro_hero") {
            Column {
                Spacer(Modifier.height(20.dp))
                ProNowCard(heroSong, heroIsCurrent, heroIsPlaying, recentlyPlayed.isNotEmpty(), onPlayHero)
            }
        }
    }

    item(key = "pro_actions") {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProAction(Icons.Rounded.Favorite, "Liked Songs", Modifier.weight(1f), onLiked)
                ProAction(Icons.Rounded.Shuffle, "Shuffle All", Modifier.weight(1f), onShuffleAll)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProAction(Icons.Rounded.AccessTime, "Recently Added", Modifier.weight(1f), onRecentlyAdded)
                ProAction(Icons.Rounded.Diamond, "Hi-Res Vault", Modifier.weight(1f), onHiResVault)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProAction(Icons.Rounded.Refresh, "Rediscover", Modifier.weight(1f), onRediscover)
                ProAction(Icons.Rounded.Cloud, "Cloud Library", Modifier.weight(1f)) { onNavigate(LibraryView.CLOUD) }
            }
        }
    }

    val mixes = buildList {
        if (quickPicks.isNotEmpty()) add(Triple("Quick picks", "Fresh selections for right now", quickPicks))
        if (rediscoverSongs.isNotEmpty()) add(Triple("Rediscover", "Songs you have not played lately", rediscoverSongs))
        if (hiResSongs.isNotEmpty()) add(Triple("Hi-Res mix", "24-bit and up", hiResSongs))
    }
    if (mixes.isNotEmpty()) {
        item(key = "pro_mix_title") { ProSectionHeader("Made for you") }
        items(mixes.size, key = { "pro_mix_$it" }) { i ->
            val (title, sub, list) = mixes[i]
            ProRow(
                leading = {
                    val shape = MaterialTheme.shapes.small
                    Box(
                        Modifier.size(44.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), shape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                },
                title = title,
                subtitle = sub,
                trailing = {
                    Text(
                        "${list.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        maxLines = 1, softWrap = false
                    )
                },
                onClick = { onPlayList(list, 0) }
            )
        }
    }

    if (recentlyPlayed.isNotEmpty()) {
        item(key = "pro_again_title") {
            ProSectionHeader("Listen again", "View all") { onNavigate(LibraryView.RECENTLY_PLAYED) }
        }
        item(key = "pro_again") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recentlyPlayed.size) { i -> SquareSong(recentlyPlayed[i]) { onPlayList(recentlyPlayed, i) } }
            }
        }
    }

    item(key = "pro_mood_title") { ProSectionHeader("Browse by mood", "See all") { onMood("") } }
    item(key = "pro_mood") {
        val shape = MaterialTheme.shapes.small
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            proMoods.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { label ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(shape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, lineColor(), shape)
                                .clickable { onMood(label) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    if (hiResSongs.isNotEmpty()) {
        item(key = "pro_hires_title") { ProSectionHeader("Hi-Res Vault", "Play all", onHiResVault) }
        val shown = hiResSongs.take(4)
        items(shown.size, key = { "pro_hires_${shown[it].id}" }) { i ->
            val song = shown[i]
            ProRow(
                leading = {
                    ProCover(
                        song.albumArtUri, MaterialTheme.shapes.small, Modifier.size(48.dp),
                        letter = song.title.trim().take(1).uppercase(Locale.getDefault())
                    )
                },
                title = song.title,
                subtitle = song.artist,
                trailing = { ProTag("${song.bitDepth}/${khz(song.sampleRateHz)}", highlight = true) },
                onClick = { onPlayList(hiResSongs, i) }
            )
        }
    }

    if (artists.isNotEmpty()) {
        item(key = "pro_artists_title") { ProSectionHeader("Artists", "View all") { onNavigate(LibraryView.ARTISTS) } }
        item(key = "pro_artists") {
            val top = artists.take(10)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(top.size) { i ->
                    val artist = top[i]
                    Column(
                        Modifier.width(92.dp).clickable { onArtist(artist.first) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ProCover(
                            artist.third, CircleShape, Modifier.size(92.dp),
                            letter = artist.first.trim().take(1).uppercase(Locale.getDefault()), letterSize = 34
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            artist.first, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (decades.isNotEmpty()) {
        item(key = "pro_decades_title") { ProSectionHeader("Through the decades") }
        item(key = "pro_decades") {
            val shape = MaterialTheme.shapes.medium
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(decades.size) { i ->
                    val bucket = decades[i]
                    Column(
                        Modifier
                            .width(132.dp)
                            .height(78.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, lineColor(), shape)
                            .clickable { onPlayList(bucket.items, 0) }
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            bucket.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, softWrap = false
                        )
                        Text(
                            "${bucket.items.size} tracks", color = MaterialTheme.colorScheme.primary, fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false
                        )
                    }
                }
            }
        }
    }
}
