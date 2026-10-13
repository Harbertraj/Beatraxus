package com.beatraxus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.Song
import com.beatraxus.app.utils.ImageUtils

/** How many songs the "Popular" block shows at most. */
private const val POPULAR_LIMIT = 5

/** Minimum number of songs before a "Popular" block is worth showing. */
private const val POPULAR_MIN_SONGS = 6

/**
 * Local "popular" ranking. The app does not store per-song play counts, so popularity
 * is derived from the signals that are available on-device:
 *  - favourites rank first
 *  - then songs from the recent play history (most recent first)
 * Returns an empty list when there is no signal (the UI then hides the section).
 */
fun popularSongsOf(
    songs: List<Song>,
    favoriteIds: Collection<String>,
    recentlyPlayed: List<Song>,
    limit: Int = POPULAR_LIMIT
): List<Song> {
    if (songs.size < POPULAR_MIN_SONGS) return emptyList()
    val favSet = favoriteIds.toHashSet()
    val recentRank = HashMap<String, Int>()
    recentlyPlayed.forEachIndexed { i, s -> recentRank.putIfAbsent(s.id, recentlyPlayed.size - i) }
    return songs
        .map { s ->
            val score = (if (s.id in favSet) 1000 else 0) + (recentRank[s.id] ?: 0)
            s to score
        }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}

/**
 * Number of LazyColumn items placed before the first regular song row:
 * header + (popular label + popular rows + "all songs" label, if any popular songs).
 */
fun detailHeaderItemCount(popularCount: Int): Int =
    if (popularCount > 0) 1 + 1 + popularCount + 1 else 1

fun detailSubtitle(songs: List<Song>, isArtist: Boolean): String {
    val totalMs = songs.sumOf { it.durationMs }
    val totalMin = (totalMs / 60_000L).toInt()
    val duration = if (totalMin >= 60) "${totalMin / 60} hr ${totalMin % 60} min" else "$totalMin min"
    val count = "${songs.size} ${if (songs.size == 1) "song" else "songs"}"
    return if (isArtist) {
        val albums = songs.map { it.album }.distinct().size
        "$count • $albums ${if (albums == 1) "album" else "albums"} • $duration"
    } else {
        val year = songs.firstOrNull { it.year > 0 }?.year
        listOfNotNull(year?.toString(), count, duration).joinToString(" • ")
    }
}

/**
 * OTT-style header for album / artist detail screens: big centred artwork (online art first, local
 * art as fallback), title, optional artist link line, meta lines, an expandable "about" text and
 * finally the library action strip (Shuffle All, sort, cloud, density).
 *
 * The header itself is transparent: the colour wash comes from [DetailBackdrop] which is drawn
 * behind the whole screen, so the top bar and the header share the same background.
 */
@Composable
fun LibraryDetailHeader(
    title: String,
    artModels: List<Any?>,
    isArtist: Boolean,
    localMeta: String,
    modifier: Modifier = Modifier,
    onlineMeta: String? = null,
    about: String? = null,
    aboutSource: String? = null,
    aboutUrl: String? = null,
    artistLine: (@Composable () -> Unit)? = null,
    actionStrip: @Composable () -> Unit
) {
    val models = remember(artModels) { artModels.filterNotNull() }
    var modelIndex by remember(models) { mutableIntStateOf(0) }
    var allFailed by remember(models) { mutableStateOf(false) }
    val defaultArt = remember { ImageUtils.getDefaultAlbumArtRes() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val artShape = if (isArtist) CircleShape else RoundedCornerShape(20.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
                .shadow(18.dp, artShape, clip = false)
                .clip(artShape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), artShape),
            contentAlignment = Alignment.Center
        ) {
            if (isArtist && (models.isEmpty() || allFailed)) {
                ArtistAvatar(name = title, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            } else {
                AsyncImage(
                    model = models.getOrNull(modelIndex),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    error = painterResource(defaultArt),
                    fallback = painterResource(defaultArt),
                    onError = {
                        if (modelIndex < models.lastIndex) modelIndex++ else allFailed = true
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (artistLine != null) {
            Spacer(Modifier.height(2.dp))
            artistLine()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = localMeta,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!onlineMeta.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = onlineMeta,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!about.isNullOrBlank()) {
            var expanded by remember(about) { mutableStateOf(false) }
            val uriHandler = LocalUriHandler.current
            Spacer(Modifier.height(10.dp))
            Text(
                text = about,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Start,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) "Show less" else "Show more",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp)
                )
                if (!aboutSource.isNullOrBlank()) {
                    Text(
                        text = "Source: $aboutSource",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        textDecoration = if (aboutUrl != null) TextDecoration.Underline else null,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .then(
                                if (aboutUrl != null) Modifier.clickable { runCatching { uriHandler.openUri(aboutUrl) } }
                                else Modifier
                            )
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        actionStrip()
    }
}

@Composable
fun DetailSectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
