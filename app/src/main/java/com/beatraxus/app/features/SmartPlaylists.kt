package com.beatraxus.app.features

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.components.AlbumArtImage
import com.beatraxus.app.ui.components.DropdownHeader
import com.beatraxus.app.ui.components.DropdownRow
import com.beatraxus.app.ui.screens.SongGridItem

/** Per-song play counter. Stored in SharedPreferences, so no database migration is needed. */
object PlayStatsStore {
    private const val PREFS = "beatraxus_play_stats"

    fun record(context: Context, songId: String) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putInt("c_$songId", p.getInt("c_$songId", 0) + 1)
            .putLong("t_$songId", System.currentTimeMillis())
            .apply()
    }

    fun count(context: Context, songId: String): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("c_$songId", 0)

    fun lastPlayed(context: Context, songId: String): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("t_$songId", 0L)
}

enum class SmartPlaylistKind(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color
) {
    MOST_PLAYED("Most Played", "Your top tracks", Icons.Rounded.LocalFireDepartment, Color(0xFFFF7043)),
    NEVER_PLAYED("Never Played", "Songs waiting to be heard", Icons.Rounded.AutoAwesome, Color(0xFF7C4DFF)),
    FORGOTTEN_FAVORITES("Forgotten Favorites", "Liked, but not played in 30 days", Icons.Rounded.Favorite, Color(0xFFFF4081)),
    HI_RES("Hi-Res Audio", "24-bit or 88.2 kHz and above", Icons.Rounded.HighQuality, Color(0xFFFFD54F)),
    LOSSLESS("Lossless", "FLAC, ALAC, WAV, AIFF, APE, DSD", Icons.Rounded.GraphicEq, Color(0xFF00E5FF)),
    LONG_TRACKS("Long Tracks", "8 minutes and longer", Icons.Rounded.HourglassBottom, Color(0xFF69F0AE)),
    QUICK_HITS("Quick Hits", "Under 2 minutes", Icons.Rounded.Timelapse, Color(0xFFB2FF59))
}

private val LOSSLESS_FORMATS = setOf("flac", "alac", "wav", "wave", "aiff", "aif", "ape", "wv", "dsf", "dff", "dsd", "tta")

fun buildSmartPlaylist(context: Context, kind: SmartPlaylistKind, songs: List<Song>): List<Song> {
    val now = System.currentTimeMillis()
    val thirtyDays = 30L * 24 * 60 * 60 * 1000
    return when (kind) {
        SmartPlaylistKind.MOST_PLAYED -> songs
            .map { it to PlayStatsStore.count(context, it.id) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(100)
            .map { it.first }
        SmartPlaylistKind.NEVER_PLAYED -> songs.filter { PlayStatsStore.count(context, it.id) == 0 }
        SmartPlaylistKind.FORGOTTEN_FAVORITES -> songs.filter {
            it.isFavorite && now - PlayStatsStore.lastPlayed(context, it.id) > thirtyDays
        }
        SmartPlaylistKind.HI_RES -> songs.filter { it.bitDepth >= 24 || it.sampleRateHz >= 88200 }
        SmartPlaylistKind.LOSSLESS -> songs.filter { it.format.lowercase().removePrefix(".") in LOSSLESS_FORMATS }
        SmartPlaylistKind.LONG_TRACKS -> songs.filter { it.durationMs >= 8 * 60_000L }.sortedByDescending { it.durationMs }
        SmartPlaylistKind.QUICK_HITS -> songs.filter { it.durationMs in 1 until 120_000L }.sortedBy { it.durationMs }
    }
}

/**
 * Smart Playlists screen. Plug into the library with the SMART_PLAYLISTS view.
 *
 * It only shows the smart-playlist chips, the selected list and its songs. Shuffle All, the filter
 * (which smart playlist) and the layout density live in the library's shared top icon strip, so the
 * selected playlist ([selectedOrdinal]) is owned by the caller and the resulting list is reported
 * back through [onCurrentListChange] for the strip's Shuffle All.
 *
 * [layoutDensity] follows the library setting: 1-2 = list (2 is compact), 3+ = grid with that many columns.
 */
@Composable
fun SmartPlaylistsScreen(
    songs: List<Song>,
    selectedOrdinal: Int,
    onSelectedOrdinalChange: (Int) -> Unit,
    layoutDensity: Int,
    currentSongId: String?,
    isPlaying: Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onCurrentListChange: (List<Song>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val kinds = remember { SmartPlaylistKind.values().toList() }
    val lists = remember(songs) {
        kinds.associateWith { buildSmartPlaylist(context, it, songs) }
    }
    val current = kinds[selectedOrdinal.coerceIn(0, kinds.lastIndex)]
    val list = lists[current].orEmpty()

    LaunchedEffect(list) { onCurrentListChange(list) }

    Column(modifier.fillMaxSize()) {
        // Scrollable options
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(kinds, key = { it.ordinal }) { kind ->
                val isSelected = kind == current
                val count = lists[kind]?.size ?: 0
                val shape = RoundedCornerShape(50)
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (isSelected) kind.color.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f))
                        .border(1.dp, if (isSelected) kind.color.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.1f), shape)
                        .clickable { onSelectedOrdinalChange(kind.ordinal) }
                        .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(kind.icon, null, tint = kind.color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        kind.title,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("$count", color = kind.color, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }

        // Selected playlist header (no Play / Shuffle buttons: Shuffle All is in the top icon strip)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(current.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${list.size} songs · ${current.subtitle}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing here yet", color = Color.White.copy(alpha = 0.5f))
            }
        } else if (layoutDensity >= 3) {
            val columns = layoutDensity.coerceIn(3, 6)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
                    SongGridItem(
                        song = song,
                        isCurrent = currentSongId == song.id,
                        isPlaying = isPlaying && currentSongId == song.id,
                        isCompact = layoutDensity >= 5,
                        onClick = { onPlay(list, index) }
                    )
                }
            }
        } else {
            val compact = layoutDensity == 2
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onPlay(list, index) }.padding(vertical = if (compact) 3.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtImage(song = song, size = if (compact) 38.dp else 48.dp, cornerRadius = 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title,
                                color = if (currentSongId == song.id) current.color else Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium
                            )
                            Text(song.artist, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        if (current == SmartPlaylistKind.MOST_PLAYED) {
                            Text("${PlayStatsStore.count(context, song.id)}×", color = current.color, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

/**
 * Content of the top strip's filter dropdown while the Smart Playlists view is open: lets the user
 * pick which smart playlist is shown.
 */
@Composable
fun SmartFilterDropdownContent(
    selectedOrdinal: Int,
    onSelect: (Int) -> Unit
) {
    DropdownHeader(title = "SMART PLAYLIST")
    SmartPlaylistKind.values().forEach { kind ->
        DropdownRow(
            icon = kind.icon,
            label = kind.title,
            selected = kind.ordinal == selectedOrdinal,
            accent = kind.color,
            onClick = { onSelect(kind.ordinal) }
        )
    }
}
