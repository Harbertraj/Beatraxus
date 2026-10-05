package com.beatraxus.app.features

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * Self-contained Smart Playlists screen. Plug into the library with the SMART_PLAYLISTS view.
 */
@Composable
fun SmartPlaylistsScreen(
    songs: List<Song>,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<SmartPlaylistKind?>(null) }
    val lists = remember(songs) {
        SmartPlaylistKind.values().associateWith { buildSmartPlaylist(context, it, songs) }
    }
    val current = selected

    if (current == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(SmartPlaylistKind.values().toList()) { _, kind ->
                val count = lists[kind]?.size ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .clickable { selected = kind }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(kind.color.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(kind.icon, null, tint = kind.color, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kind.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(kind.subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    Text("$count", color = kind.color, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
        }
    } else {
        val list = lists[current].orEmpty()
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(current.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${list.size} songs", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                if (list.isNotEmpty()) {
                    IconButton(onClick = { onShuffle(list) }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = Color.White) }
                    Box(
                        modifier = Modifier.padding(end = 8.dp).size(40.dp).clip(CircleShape).background(current.color)
                            .clickable { onPlay(list, 0) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.Black) }
                }
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = Color.White.copy(alpha = 0.5f))
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { onPlay(list, index) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtImage(song = song, size = 48.dp, cornerRadius = 10.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
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
}
