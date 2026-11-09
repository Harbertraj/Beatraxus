package com.beatraxus.app.ui.minimal

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.LibraryMode
import com.beatraxus.app.model.LibraryView
import com.beatraxus.app.model.PlaybackMode
import com.beatraxus.app.model.Playlist
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.components.PlaybackModeSelector
import com.beatraxus.app.ui.theme.Icon
import com.beatraxus.app.utils.DecadeBucket
import java.util.Locale

/*
 * "Minimal" layout: the Home Redesign mockups, built as Compose screens.
 *
 * A theme file only holds data, so these layouts are code. They are shown only while the theme with the id
 * "minimal" is the active theme (see [isMinimal]); every other theme and every user without it keeps the
 * existing screens untouched.
 */

/** True while the "Minimal" theme from the Beatraxus-themes store is applied. */
val AppearanceConfig.isMinimal: Boolean get() = activeTheme?.id == MINIMAL_THEME_ID

const val MINIMAL_THEME_ID = "minimal"

internal val MinPink = Color(0xFFFF4D9D)
internal val MinViolet = Color(0xFF9B5CF6)
internal val MinBg = Color(0xFF0A0A12)
internal val MinCard = Color(0xFF1A1A24)
internal val MinChip = Color(0xFF1C1C26)
internal val MinMuted = Color(0xFFA9A7B8)
internal val MinGold = Color(0xFFFFC83D)
internal val MinCyan = Color(0xFF22D3EE)
internal val MinMint = Color(0xFF3DEB9A)
private val MinCoral = Color(0xFFFF8A8A)

/** Video screens that show the floating nav pill under the Minimal theme. */
val minimalVideoNavViews: Set<LibraryView> = setOf(
    LibraryView.HOME, LibraryView.VIDEO_ALL, LibraryView.VIDEO_FOLDERS,
    LibraryView.VIDEO_RECENTLY_ADDED, LibraryView.VIDEO_RECENTLY_PLAYED
)

/** Library screens the Minimal layout draws itself (the rest keep their normal look). */
val minimalPageViews: Set<LibraryView> = setOf(
    LibraryView.ALL_SONGS, LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
    LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS, LibraryView.FAVORITES
)

/** Top tint of each screen's background in the mockups; it fades into [MinBg]. */
fun minimalTint(view: LibraryView): Color = when (view) {
    LibraryView.HOME -> Color(0xFF3E306E)
    LibraryView.ALL_SONGS -> Color(0xFF531E3C)
    LibraryView.ALBUMS -> Color(0xFF382B61)
    LibraryView.ARTISTS -> Color(0xFF083D45)
    LibraryView.FOLDERS -> Color(0xFF493721)
    LibraryView.YEARS -> Color(0xFF193059)
    LibraryView.GENRES -> Color(0xFF13361E)
    LibraryView.PLAYLISTS -> Color(0xFF312759)
    LibraryView.FAVORITES -> Color(0xFF58252C)
    else -> Color(0xFF312759)
}

private val coverGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
    listOf(Color(0xFF06B6D4), Color(0xFF3B82F6)),
    listOf(Color(0xFFFFB347), Color(0xFFFF6F91)),
    listOf(Color(0xFF22C55E), Color(0xFF14B8A6)),
    listOf(Color(0xFF6366F1), Color(0xFFA855F7)),
    listOf(Color(0xFFF472B6), Color(0xFF8B5CF6)),
    listOf(Color(0xFF14B8A6), Color(0xFF3B82F6)),
    listOf(Color(0xFFFBBF24), Color(0xFFF43F5E))
)

private val cardGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF6B3FD0), Color(0xFF9B5CC8)),
    listOf(Color(0xFF1B7A8C), Color(0xFF3B5BB5)),
    listOf(Color(0xFFB0294F), Color(0xFFC4501F)),
    listOf(Color(0xFF1F8F5A), Color(0xFF1A8A8A)),
    listOf(Color(0xFF4B3FC9), Color(0xFF6A4FD8)),
    listOf(Color(0xFFC4501F), Color(0xFFB0294F)),
    listOf(Color(0xFF1F8F5A), Color(0xFF2A9D8F)),
    listOf(Color(0xFF5B3FC9), Color(0xFF3B6BB5))
)

internal fun minimalGradient(seed: String): List<Color> =
    coverGradients[(seed.hashCode() and Int.MAX_VALUE) % coverGradients.size]

private fun cardGradient(seed: String): List<Color> =
    cardGradients[(seed.hashCode() and Int.MAX_VALUE) % cardGradients.size]

private val LOSSLESS_FORMATS = setOf("flac", "wav", "alac", "aiff", "aif", "ape", "wv", "dsf", "dff")

private fun formatKhz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000f)

private fun isHiRes(song: Song): Boolean = song.bitDepth >= 24 || song.sampleRateHz >= 88200

/** "FLAC 24/96", "FLAC 16", "MP3": text of the outline quality pill. */
private fun qualityText(song: Song): String {
    val raw = song.format.substringAfterLast('/').trim()
    val fmt = raw.uppercase(Locale.US).ifEmpty { "AUDIO" }
    return when {
        isHiRes(song) -> "$fmt ${song.bitDepth}/${formatKhz(song.sampleRateHz)}"
        raw.lowercase(Locale.US) in LOSSLESS_FORMATS -> "$fmt ${song.bitDepth}"
        else -> fmt
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/** What the Minimal screens need from the main screen, so call sites stay short. */
class MinimalActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onFilter: (Rect) -> Unit,
)

// ------------------------------------------------------------------ shared pieces

/** Full-screen backdrop: the near-black base with the screen's colour fading in from the top. */
@Composable
fun MinimalBackdrop(view: LibraryView, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MinBg)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(Brush.verticalGradient(listOf(minimalTint(view), Color.Transparent)))
        )
    }
}

/** Gradient cover that shows the real artwork on top when there is some. */
@Composable
internal fun MinimalCover(
    art: Uri?,
    seed: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    decor: Boolean = false,
    letter: String? = null,
    letterSize: Int = 24,
    colors: List<Color> = minimalGradient(seed),
) {
    Box(
        modifier.clip(shape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        if (decor) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 26.dp, y = 26.dp)
                    .fillMaxSize(0.62f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.14f))
            )
        }
        if (letter != null) {
            Text(letter, color = Color.White, fontSize = letterSize.sp, fontWeight = FontWeight.Bold)
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

@Composable
internal fun MinimalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (selected) MinPink else MinChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun MinimalPill(text: String, gold: Boolean = false) {
    val color = if (gold) MinGold else Color.White.copy(alpha = 0.8f)
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (gold) MinGold.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
internal fun MinimalPageHeader(
    title: String,
    actions: MinimalActions,
    subtitle: String? = null,
    showFilter: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(
                title,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = actions.onSearch) {
                Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            if (showFilter) {
                var bounds by remember { mutableStateOf(Rect.Zero) }
                IconButton(
                    onClick = { actions.onFilter(bounds) },
                    modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
                ) {
                    Icon(Icons.Rounded.FilterList, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                color = MinMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ song lists (All Songs, Favorites)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalSongsPage(
    title: String,
    songs: List<Song>,
    favorites: Set<String>,
    currentSongId: String?,
    actions: MinimalActions,
    likedHero: Boolean,
    onPlayAt: (Int) -> Unit,
    onShuffle: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onLongPress: (Song) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader(title, actions)
        LazyColumn(contentPadding = PaddingValues(bottom = 210.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                if (likedHero) {
                    LikedHero(count = songs.size, onPlay = { if (songs.isNotEmpty()) onPlayAt(0) }, onShuffle = onShuffle)
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Brush.linearGradient(listOf(MinPink, MinViolet)))
                                .clickable(onClick = onShuffle)
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Shuffle", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Row(
                            Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                                .clickable { if (songs.isNotEmpty()) onPlayAt(0) }
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Play", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${songs.size} songs", color = MinMuted, fontSize = 14.sp)
                    }
                }
            }
            items(songs.size, key = { songs[it].id }) { index ->
                val song = songs[index]
                val current = song.id == currentSongId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onPlayAt(index) }, onLongClick = { onLongPress(song) })
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MinimalCover(song.albumArtUri, song.id, RoundedCornerShape(14.dp), Modifier.size(56.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = if (current) MinPink else Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(song.artist, color = MinMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (likedHero) {
                        IconButton(onClick = { onToggleFavorite(song) }) {
                            val liked = favorites.contains(song.id)
                            Icon(
                                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                null,
                                tint = if (liked) Color(0xFFFF5A6E) else MinMuted,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            MinimalPill(qualityText(song), gold = isHiRes(song))
                            Spacer(Modifier.height(4.dp))
                            Text(formatDuration(song.durationMs), color = MinMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedHero(count: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(136.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFF4D9D), Color(0xFF9B5CF6))))
    ) {
        Icon(
            Icons.Rounded.Favorite, null,
            tint = Color.White.copy(alpha = 0.22f),
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 24.dp).size(150.dp)
        )
        Column(Modifier.padding(18.dp)) {
            Text("YOUR LIKED SONGS", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
            Text("$count songs", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White)
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)).clickable(onClick = onShuffle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ albums

@Composable
fun MinimalAlbumsPage(
    albums: List<Triple<String, String, Uri?>>,
    songs: List<Song>,
    actions: MinimalActions,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    var sort by remember { mutableIntStateOf(0) }
    val info = remember(songs) {
        songs.groupBy { it.album }.mapValues { (_, list) ->
            AlbumInfo(
                year = list.maxOf { it.year },
                added = list.maxOf { it.dateAdded },
                badge = list.firstOrNull { isHiRes(it) }?.let { "HI-RES" }
                    ?: list.first().format.substringAfterLast('/').uppercase(Locale.US).ifEmpty { null }
            )
        }
    }
    val shown = remember(albums, sort, info) {
        when (sort) {
            1 -> albums.sortedBy { it.first.lowercase() }
            2 -> albums.sortedByDescending { info[it.first]?.year ?: 0 }
            else -> albums.sortedByDescending { info[it.first]?.added ?: 0L }
        }
    }
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader("Albums", actions)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 210.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                    MinimalChip("Recent", sort == 0) { sort = 0 }
                    MinimalChip("A to Z", sort == 1) { sort = 1 }
                    MinimalChip("Year", sort == 2) { sort = 2 }
                }
            }
            items(shown, key = { it.first + "|" + it.second }) { album ->
                val meta = info[album.first]
                Column(
                    Modifier.combinedClickableCompat(onClick = { onOpen(album.first) }, onLongClick = { onLongPress(album.first) })
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        MinimalCover(album.third, album.first, RoundedCornerShape(22.dp), Modifier.fillMaxSize(), decor = true)
                        meta?.badge?.let { badge ->
                            val hi = badge == "HI-RES"
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (hi) MinGold else Color.Black.copy(alpha = 0.45f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(badge, color = if (hi) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(album.first, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val year = meta?.year ?: 0
                    Text(
                        if (year > 0) "${album.second} · $year" else album.second,
                        color = MinMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private class AlbumInfo(val year: Int, val added: Long, val badge: String?)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

// ------------------------------------------------------------------ artists

@Composable
fun MinimalArtistsPage(
    artists: List<Triple<String, String, Uri?>>,
    actions: MinimalActions,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader("Artists", actions, subtitle = "${artists.size} artists")
        LazyColumn(contentPadding = PaddingValues(bottom = 210.dp), modifier = Modifier.fillMaxSize()) {
            items(artists, key = { it.first }) { artist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickableCompat(onClick = { onOpen(artist.first) }, onLongClick = { onLongPress(artist.first) })
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MinimalCover(
                        artist.third, artist.first, CircleShape, Modifier.size(58.dp),
                        letter = artist.first.trim().take(1).uppercase(Locale.getDefault()), letterSize = 24
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artist.first, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist.second, color = MinMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MinMuted, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ folders

@Composable
fun MinimalFoldersPage(
    folders: List<Triple<String, String, Uri?>>,
    songs: List<Song>,
    actions: MinimalActions,
    onOpen: (path: String, name: String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val counts = remember(songs) { songs.groupingBy { it.folder }.eachCount() }
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader("Folders", actions, showFilter = false)
        Row(Modifier.padding(start = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Internal storage", color = Color(0xFFFFB347), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MinMuted, modifier = Modifier.size(16.dp))
            Text("All folders", color = MinMuted, fontSize = 13.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 210.dp), modifier = Modifier.fillMaxSize()) {
            items(folders, key = { it.first }) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickableCompat(onClick = { onOpen(folder.first, folder.second) }, onLongClick = { onLongPress(folder.first) })
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFFFB347).copy(alpha = 0.18f))
                            .border(1.dp, Color(0xFFFFB347).copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Folder, null, tint = Color(0xFFFFB347), modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(folder.second, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(folder.first, color = MinMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    counts[folder.first]?.let { Text("$it", color = MinMuted, fontSize = 14.sp) }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MinMuted, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ years

@Composable
fun MinimalYearsPage(
    years: List<Triple<String, String, Uri?>>,
    actions: MinimalActions,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val filters = listOf("All", "2020s", "2010s", "2000s", "90s", "80s")
    var selected by remember { mutableIntStateOf(0) }
    val shown = remember(years, selected) {
        val start = when (selected) { 1 -> 2020; 2 -> 2010; 3 -> 2000; 4 -> 1990; 5 -> 1980; else -> -1 }
        if (start < 0) years else years.filter { y -> y.first.toIntOrNull()?.let { it in start until start + 10 } == true }
    }
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader("Years", actions, showFilter = false)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 210.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                    items(filters.size) { i -> MinimalChip(filters[i], selected == i) { selected = i } }
                }
            }
            items(shown, key = { it.first }) { year ->
                val index = years.indexOf(year)
                ColorCard(
                    colors = cardGradients[index.coerceAtLeast(0) % cardGradients.size],
                    modifier = Modifier.height(96.dp).combinedClickableCompat(
                        onClick = { onOpen(year.first) }, onLongClick = { onLongPress(year.first) }
                    ),
                    corner = 20.dp
                ) {
                    Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                        Text(year.first, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Text(year.second, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Rounded gradient card with the soft circle in its corner that the mockups use. */
@Composable
private fun ColorCard(
    colors: List<Color>,
    modifier: Modifier,
    corner: Dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(colors))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(corner))
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 22.dp, y = 22.dp)
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
        )
        content()
    }
}

// ------------------------------------------------------------------ genres

@Composable
fun MinimalGenresPage(
    genres: List<Triple<String, String, Uri?>>,
    actions: MinimalActions,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader("Genres", actions, subtitle = "Found by the on-device analysis and your tags", showFilter = false)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 210.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(genres, key = { it.first }) { genre ->
                ColorCard(
                    colors = cardGradient(genre.first),
                    modifier = Modifier.height(100.dp).combinedClickableCompat(
                        onClick = { onOpen(genre.first) }, onLongClick = { onLongPress(genre.first) }
                    ),
                    corner = 20.dp
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-6).dp, y = 4.dp)
                            .size(44.dp)
                            .rotate(18f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp)) {
                        Text(genre.first, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(genre.second, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ playlists

@Composable
fun MinimalPlaylistsPage(
    playlists: List<Playlist>,
    actions: MinimalActions,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MinimalPageHeader("Playlists", actions, showFilter = false)
        LazyColumn(contentPadding = PaddingValues(bottom = 210.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "new") {
                val dash = Color.White.copy(alpha = 0.3f)
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .drawBehind {
                            drawRoundRect(
                                color = dash,
                                cornerRadius = CornerRadius(20.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                            )
                        }
                        .clickable(onClick = onCreate)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(MinPink, MinViolet))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("New playlist", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickableCompat(onClick = { onOpen(playlist.name) }, onLongClick = { onLongPress(playlist.id) })
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Mosaic(playlist.id, Modifier.size(56.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.songIds.size} songs", color = MinMuted, fontSize = 13.sp)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MinMuted, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/** Four-colour 2 x 2 tile used as a playlist cover. */
@Composable
private fun Mosaic(seed: String, modifier: Modifier) {
    val tones = remember(seed) { List(4) { coverGradients[((seed + it).hashCode() and Int.MAX_VALUE) % coverGradients.size][it % 2] } }
    Column(modifier.clip(RoundedCornerShape(14.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(tones[0]))
            Box(Modifier.weight(1f).fillMaxHeight().background(tones[1]))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(tones[2]))
            Box(Modifier.weight(1f).fillMaxHeight().background(tones[3]))
        }
    }
}

// ------------------------------------------------------------------ bottom navigation

@Composable
fun MinimalBottomNav(
    currentView: LibraryView,
    modifier: Modifier = Modifier,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onRadio: () -> Unit,
    video: Boolean = false,
) {
    val selected = when (currentView) {
        LibraryView.HOME -> 0
        LibraryView.RADIO, LibraryView.VIDEO_FOLDERS, LibraryView.VIDEO_FOLDER_DETAIL -> 3
        else -> 2
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(31.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE63A3A47), Color(0xE61F1F29))))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(31.dp))
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(Icons.Rounded.Home, "Home", selected == 0, onHome)
        NavItem(Icons.Rounded.Search, "Search", false, onSearch)
        NavItem(if (video) Icons.Rounded.VideoLibrary else Icons.Rounded.LibraryMusic, "Library", selected == 2, onLibrary)
        NavItem(if (video) Icons.Rounded.Folder else Icons.Rounded.Radio, if (video) "Folders" else "Radio", selected == 3, onRadio)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) MinPink else Color.White.copy(alpha = 0.85f)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ drawer

@Composable
fun MinimalDrawerMenu(
    currentView: LibraryView,
    libraryMode: LibraryMode,
    playbackMode: PlaybackMode,
    onSelectView: (LibraryView) -> Unit,
    onSetLibraryMode: (LibraryMode) -> Unit,
    onSetPlaybackMode: (PlaybackMode) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onNavigateToPlayTogether: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A2064), Color(0xFF14102A), MinBg)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        val video = playbackMode == PlaybackMode.VIDEO
        val pick: (LibraryView) -> Unit = { v -> onSelectView(v); onClose() }
        // Greeting card with the Device / Cloud / All switch.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6A2D7A), Color(0xFF3C2A74))))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MinPink, MinViolet))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (video) Icons.Rounded.Movie else Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (video) "Your" else "Hello,", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, softWrap = false)
                    Text(
                        if (video) "Cinema" else "Audiophile",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Device / Cloud / All: three equal icon segments. The drawer is narrow, so labels would not fit.
            Row(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(3.dp)
            ) {
                ModeSegment(Icons.Rounded.PhoneAndroid, "Device", libraryMode == LibraryMode.LOCAL) { onSetLibraryMode(LibraryMode.LOCAL) }
                ModeSegment(Icons.Rounded.Cloud, "Cloud", libraryMode == LibraryMode.CLOUD) { onSetLibraryMode(LibraryMode.CLOUD) }
                ModeSegment(Icons.Rounded.AllInclusive, "All (Device + Cloud)", libraryMode == LibraryMode.COMBINED) { onSetLibraryMode(LibraryMode.COMBINED) }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
          if (video) {
            DrawerLabel("VIDEO LIBRARY")
            DrawerRow(Icons.Rounded.Home, "Home", currentView == LibraryView.HOME) { pick(LibraryView.HOME) }
            DrawerRow(Icons.Rounded.Movie, "All Videos", currentView == LibraryView.VIDEO_ALL) { pick(LibraryView.VIDEO_ALL) }
            DrawerRow(Icons.Rounded.FolderOpen, "Folders", currentView == LibraryView.VIDEO_FOLDERS || currentView == LibraryView.VIDEO_FOLDER_DETAIL) { pick(LibraryView.VIDEO_FOLDERS) }
            DrawerRow(Icons.Rounded.NewReleases, "Recently Added", currentView == LibraryView.VIDEO_RECENTLY_ADDED) { pick(LibraryView.VIDEO_RECENTLY_ADDED) }
            DrawerRow(Icons.Rounded.History, "Recently Played", currentView == LibraryView.VIDEO_RECENTLY_PLAYED) { pick(LibraryView.VIDEO_RECENTLY_PLAYED) }
            DrawerRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Video Playlists", currentView == LibraryView.PLAYLISTS) { pick(LibraryView.PLAYLISTS) }
            DrawerRow(Icons.Rounded.LiveTv, "Live TV", currentView == LibraryView.LIVE_TV) { pick(LibraryView.LIVE_TV) }
            DrawerRow(Icons.Rounded.Extension, "Add-ons", currentView == LibraryView.ADDONS) { pick(LibraryView.ADDONS) }
          } else {
            DrawerLabel("LIBRARY")
            DrawerRow(Icons.Rounded.MusicNote, "All Songs", currentView == LibraryView.ALL_SONGS) { pick(LibraryView.ALL_SONGS) }
            DrawerRow(Icons.Rounded.Album, "Albums", currentView == LibraryView.ALBUMS) { pick(LibraryView.ALBUMS) }
            DrawerRow(Icons.Rounded.Person, "Artists", currentView == LibraryView.ARTISTS) { pick(LibraryView.ARTISTS) }
            DrawerRow(Icons.Rounded.Folder, "Folders", currentView == LibraryView.FOLDERS) { pick(LibraryView.FOLDERS) }
            DrawerLabel("BROWSE")
            DrawerRow(Icons.Rounded.CalendarToday, "Years", currentView == LibraryView.YEARS) { pick(LibraryView.YEARS) }
            DrawerRow(Icons.Rounded.GridView, "Genres", currentView == LibraryView.GENRES) { pick(LibraryView.GENRES) }
            DrawerRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlists", currentView == LibraryView.PLAYLISTS) { pick(LibraryView.PLAYLISTS) }
            DrawerRow(Icons.Rounded.FavoriteBorder, "Favorite Songs", currentView == LibraryView.FAVORITES) { pick(LibraryView.FAVORITES) }
            DrawerLabel("MORE")
            DrawerRow(Icons.Rounded.AutoAwesome, "Smart Playlists", currentView == LibraryView.SMART_PLAYLISTS) { pick(LibraryView.SMART_PLAYLISTS) }
            DrawerRow(Icons.Rounded.NewReleases, "Recently Added", currentView == LibraryView.RECENTLY_ADDED) { pick(LibraryView.RECENTLY_ADDED) }
            DrawerRow(Icons.Rounded.History, "Recently Played", currentView == LibraryView.RECENTLY_PLAYED) { pick(LibraryView.RECENTLY_PLAYED) }
            DrawerRow(Icons.Rounded.Radio, "Radio", currentView == LibraryView.RADIO) { pick(LibraryView.RADIO) }
            DrawerRow(Icons.Rounded.Storage, "SMB / NAS", currentView == LibraryView.SMB_NAS) { pick(LibraryView.SMB_NAS) }
            DrawerRow(Icons.Rounded.Dns, "FTP / SFTP", currentView == LibraryView.FTP_SFTP) { pick(LibraryView.FTP_SFTP) }
            DrawerRow(Icons.Rounded.Extension, "Add-ons", currentView == LibraryView.ADDONS) { pick(LibraryView.ADDONS) }
            DrawerRow(Icons.Rounded.Groups, "Play Together", false) { onClose(); onNavigateToPlayTogether() }
          }
            Spacer(Modifier.height(10.dp))
            PlaybackModeSelector(
                currentMode = playbackMode,
                onModeSelected = onSetPlaybackMode,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
        }

        // Footer: appearance, add-ons, sound settings.
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(25.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onClose(); onNavigateToSettings() }) {
                Icon(Icons.Rounded.Palette, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { pick(LibraryView.ADDONS) }) {
                Icon(Icons.Rounded.Download, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = { onClose(); onNavigateToDsp() }) {
                Icon(Icons.Rounded.Tune, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) MinGold else Color.White.copy(alpha = 0.75f)
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text,
        color = MinMuted,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun DrawerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .then(
                if (selected) Modifier
                    .background(Brush.linearGradient(listOf(Color(0xFF8A3A7E), Color(0xFF4A3A8C))))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) Color.White else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        // One line, shrinking a little if a long name still does not fit the narrow drawer.
        var size by remember(label) { mutableStateOf(15f) }
        Text(
            label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.9f),
            fontSize = size.sp,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { r -> if (r.didOverflowWidth && size > 10f) size -= 0.5f },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

// ------------------------------------------------------------------ home

private val moods: List<Pair<String, Color>> = listOf(
    "Calm" to Color(0xFF237A3C),
    "Focus" to Color(0xFF0E7A86),
    "Workout" to Color(0xFFB34A0E),
    "Romantic" to Color(0xFFB02A5E),
    "Late Night" to Color(0xFF3B45A3),
    "Road Trip" to Color(0xFF1F7B6B)
)

private val decadeColors: List<List<Color>> = listOf(
    listOf(Color(0xFFB0294F), Color(0xFFA02050)),
    listOf(Color(0xFF5B3FC9), Color(0xFF4B3FB9)),
    listOf(Color(0xFF0E7A86), Color(0xFF1B6A8C)),
    listOf(Color(0xFFB34A0E), Color(0xFFA03A1E)),
    listOf(Color(0xFF237A3C), Color(0xFF1F6A4C))
)

/** The big gradient card at the top of the Minimal Home (audio and video): greeting, title, stat chips, menu button. */
@Composable
fun MinimalHeaderCard(
    eyebrow: String,
    title: String,
    titleAccent: String,
    chips: List<Triple<androidx.compose.ui.graphics.vector.ImageVector, Color, String>>,
    onOpenDrawer: () -> Unit,
) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6A3A6E), Color(0xFF473A86))))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp))
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(MinCoral.copy(alpha = 0.18f)).border(1.dp, MinCoral.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.WbTwilight, null, tint = MinCoral, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    eyebrow.uppercase(Locale.getDefault()),
                    color = MinCoral, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onOpenDrawer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Menu, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
                Text(titleAccent, color = MinMint, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { (icon, tint, text) -> StatChip(icon, tint, text) }
            }
        }
    }
}

/** Section heading used by the Minimal screens (also by the video home). */
@Composable
fun MinimalSectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) =
    SectionTitle(title, action, false, onAction)

/** Flat tile with a gradient icon block, shared with the video home. */
@Composable
fun MinimalQuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: List<Color>,
    modifier: Modifier,
    onClick: () -> Unit,
) = QuickTile(icon, label, colors, modifier, onClick)

@Composable
private fun SectionTitle(title: String, action: String? = null, chevron: Boolean = false, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, color = MinCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAction))
        } else if (chevron) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MinMuted, modifier = Modifier.size(26.dp).clickable(onClick = onAction))
        }
    }
}

@Composable
private fun QuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: List<Color>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MinCard)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(48.dp).fillMaxHeight().background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            color = Color.White,
            fontSize = 13.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
    }
}

@Composable
internal fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun JumpBackInCard(song: Song, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(224.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF8B3DF0), Color(0xFFC045C8), Color(0xFFEC4899))))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .offset(x = (-40).dp, y = 50.dp)
                .size(260.dp)
                .clip(RoundedCornerShape(64.dp))
                .background(Color.White.copy(alpha = 0.07f))
        )
        // The record: dark disc with fine grooves and the cover as its label.
        Box(Modifier.align(Alignment.CenterEnd).offset(x = 70.dp).size(250.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(Color(0xFF14121B), radius = r)
                var ring = r * 0.93f
                while (ring > r * 0.45f) {
                    drawCircle(Color.White.copy(alpha = 0.06f), radius = ring, style = Stroke(width = 1.5f))
                    ring -= r * 0.045f
                }
            }
            MinimalCover(song.albumArtUri, song.id, CircleShape, Modifier.size(96.dp), colors = listOf(MinPink, MinViolet))
        }
        Box(
            Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF3A1F6E).copy(alpha = 0.75f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("JUMP BACK IN", color = Color.White, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp, end = 120.dp)) {
            Text(song.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MixCard(title: String, subtitle: String, colors: List<Color>, onClick: () -> Unit) {
    Column(Modifier.width(176.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(176.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(colors))) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 34.dp, y = 34.dp)
                    .size(130.dp)
                    .clip(RoundedCornerShape(44.dp))
                    .background(Color.White.copy(alpha = 0.16f))
            )
            Box(
                Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("MIX", color = Color.White, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A1030).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = MinMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SquareSong(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(134.dp).clickable(onClick = onClick)) {
        MinimalCover(song.albumArtUri, song.id, RoundedCornerShape(20.dp), Modifier.size(134.dp))
        Spacer(Modifier.height(8.dp))
        Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = MinMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The Minimal Home screen, as items of the Home list. */
fun LazyListScope.minimalHomeItems(
    greeting: String,
    songCount: Int,
    favoriteCount: Int,
    albumCount: Int,
    heroSong: Song?,
    quickPicks: List<Song>,
    recentlyPlayed: List<Song>,
    hiResSongs: List<Song>,
    rediscoverSongs: List<Song>,
    artists: List<Triple<String, String, Uri?>>,
    decades: List<DecadeBucket<Song>>,
    onOpenDrawer: () -> Unit,
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
    item(key = "min_header") {
        MinimalHeaderCard(
            eyebrow = greeting,
            title = "Welcome, ",
            titleAccent = "back",
            chips = listOf(
                Triple(Icons.Rounded.MusicNote, MinPink, "%,d songs".format(Locale.getDefault(), songCount)),
                Triple(Icons.Rounded.Favorite, Color(0xFFFF5A6E), "$favoriteCount liked"),
                Triple(Icons.Rounded.Album, MinViolet, "$albumCount albums")
            ),
            onOpenDrawer = onOpenDrawer
        )
    }

    item(key = "min_chips") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp, bottom = 14.dp)
        ) {
            item { MinimalChip("All", true) {} }
            item { MinimalChip("Music", false) { onNavigate(LibraryView.ALL_SONGS) } }
            item { MinimalChip("Cloud", false) { onNavigate(LibraryView.CLOUD) } }
            item { MinimalChip("Hi-Res", false, onHiResVault) }
            item { MinimalChip("Favorites", false) { onNavigate(LibraryView.FAVORITES) } }
        }
    }

    item(key = "min_tiles") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile(Icons.Rounded.Favorite, "Liked Songs", listOf(Color(0xFFEC4899), Color(0xFF9B5CF6)), Modifier.weight(1f), onLiked)
                QuickTile(Icons.Rounded.Shuffle, "Shuffle All", listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)), Modifier.weight(1f), onShuffleAll)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile(Icons.Rounded.AccessTime, "Recently Added", listOf(Color(0xFFFF8A5C), Color(0xFFFFB347)), Modifier.weight(1f), onRecentlyAdded)
                QuickTile(Icons.Rounded.Diamond, "Hi-Res Vault", listOf(Color(0xFFFFB347), Color(0xFFFFD84D)), Modifier.weight(1f), onHiResVault)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile(Icons.Rounded.Refresh, "Rediscover", listOf(Color(0xFF14B8A6), Color(0xFF22C55E)), Modifier.weight(1f), onRediscover)
                QuickTile(Icons.Rounded.Cloud, "Cloud Library", listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)), Modifier.weight(1f)) { onNavigate(LibraryView.CLOUD) }
            }
        }
    }

    if (heroSong != null) {
        item(key = "min_hero_title") { SectionTitle("Jump back in") }
        item(key = "min_hero") { JumpBackInCard(heroSong, onPlayHero) }
    }

    val mixes = buildList {
        if (quickPicks.isNotEmpty()) add(Triple("Evening Mix", "Chill and pop picks", quickPicks))
        if (rediscoverSongs.isNotEmpty()) add(Triple("Rediscover Mix", "Songs you have not played lately", rediscoverSongs))
        if (hiResSongs.isNotEmpty()) add(Triple("Hi-Res Mix", "24-bit and up", hiResSongs))
    }
    if (mixes.isNotEmpty()) {
        item(key = "min_mix_title") { SectionTitle("Made for you", chevron = true) }
        item(key = "min_mix") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(mixes.size) { i ->
                    val (title, sub, list) = mixes[i]
                    MixCard(title, sub, coverGradients[i % coverGradients.size]) { onPlayList(list, 0) }
                }
            }
        }
    }

    if (recentlyPlayed.isNotEmpty()) {
        item(key = "min_again_title") { SectionTitle("Listen again", chevron = true, onAction = { onNavigate(LibraryView.RECENTLY_PLAYED) }) }
        item(key = "min_again") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(recentlyPlayed.size) { i -> SquareSong(recentlyPlayed[i]) { onPlayList(recentlyPlayed, i) } }
            }
        }
    }

    item(key = "min_mood_title") { SectionTitle("Browse by mood", action = "See all", onAction = { onMood("") }) }
    item(key = "min_mood") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            moods.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, color) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(76.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(color)
                                .clickable { onMood(label) }
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = (-6).dp, y = 10.dp)
                                    .size(46.dp)
                                    .rotate(18f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.16f))
                            )
                            Text(
                                label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (hiResSongs.isNotEmpty()) {
        item(key = "min_hires_title") { SectionTitle("Hi-Res Vault", action = "Play all", onAction = onHiResVault) }
        val shown = hiResSongs.take(4)
        items(shown.size, key = { "min_hires_${shown[it].id}" }) { i ->
            val song = shown[i]
            Row(
                Modifier.fillMaxWidth().clickable { onPlayList(hiResSongs, i) }.padding(horizontal = 20.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinimalCover(song.albumArtUri, song.id, RoundedCornerShape(14.dp), Modifier.size(54.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = MinMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MinimalPill("${song.bitDepth}/${formatKhz(song.sampleRateHz)}", gold = true)
            }
        }
    }

    if (artists.isNotEmpty()) {
        item(key = "min_artists_title") { SectionTitle("Artists you love", chevron = true, onAction = { onNavigate(LibraryView.ARTISTS) }) }
        item(key = "min_artists") {
            val top = artists.take(10)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(top.size) { i ->
                    val artist = top[i]
                    Column(Modifier.width(108.dp).clickable { onArtist(artist.first) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        MinimalCover(
                            artist.third, artist.first, CircleShape, Modifier.size(108.dp),
                            letter = artist.first.trim().take(1).uppercase(Locale.getDefault()), letterSize = 44
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            artist.first, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (decades.isNotEmpty()) {
        item(key = "min_decades_title") { SectionTitle("Through the decades") }
        item(key = "min_decades") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(decades.size) { i ->
                    val bucket = decades[i]
                    ColorCard(
                        colors = decadeColors[i % decadeColors.size],
                        modifier = Modifier.width(150.dp).height(88.dp).clickable { onPlayList(bucket.items, 0) },
                        corner = 16.dp
                    ) {
                        Text(
                            bucket.label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
