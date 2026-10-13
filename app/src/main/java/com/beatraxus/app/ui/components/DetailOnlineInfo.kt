package com.beatraxus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.beatraxus.app.model.Song
import com.beatraxus.app.repository.OnlineMetadataRepository
import com.beatraxus.app.util.ArtistNameUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- online info state

@Composable
fun rememberOnlineArtistInfo(name: String?): State<OnlineMetadataRepository.ArtistInfo?> =
    produceState<OnlineMetadataRepository.ArtistInfo?>(initialValue = null, key1 = name) {
        value = null
        if (!name.isNullOrBlank()) value = OnlineMetadataRepository.artistInfo(name)
    }

@Composable
fun rememberOnlineAlbumInfo(album: String?, artist: String?): State<OnlineMetadataRepository.AlbumInfo?> =
    produceState<OnlineMetadataRepository.AlbumInfo?>(initialValue = null, key1 = album, key2 = artist) {
        value = null
        if (!album.isNullOrBlank()) value = OnlineMetadataRepository.albumInfo(album, artist)
    }

fun formatCount(n: Long): String = when {
    n >= 1_000_000_000L -> String.format("%.1fB", n / 1_000_000_000.0).replace(".0B", "B")
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0).replace(".0K", "K")
    else -> n.toString()
}

// ---------------------------------------------------------------- album artist(s)

/**
 * The artist(s) an album should be credited to. Uses the album-artist tag when present, otherwise
 * the artist that appears most on the album (plus a second one with a big share). Compilations with
 * many different artists are shown as "Various Artists".
 */
fun albumArtistNames(songs: List<Song>): List<String> {
    if (songs.isEmpty()) return emptyList()

    val tagged = songs.mapNotNull { it.albumArtist?.takeIf { a -> a.isNotBlank() } }
    if (tagged.isNotEmpty()) {
        val topKey = tagged.groupingBy { ArtistNameUtils.normalizeKey(it) }.eachCount().maxByOrNull { it.value }!!.key
        val raw = tagged.first { ArtistNameUtils.normalizeKey(it) == topKey }
        if (raw.equals("Various Artists", ignoreCase = true)) return listOf("Various Artists")
        return ArtistNameUtils.splitArtists(raw)
    }

    val primaries = songs.map { ArtistNameUtils.splitArtists(it.artist).first() }
    val groups = primaries.groupBy { ArtistNameUtils.normalizeKey(it) }.values
        .sortedByDescending { it.size }
    if (groups.size > 3) return listOf("Various Artists")
    val result = mutableListOf(groups.first().first())
    groups.drop(1).firstOrNull { it.size.toFloat() / songs.size >= 0.35f }?.let { result += it.first() }
    return result
}

/** normalised key -> display name for every artist in the library. */
fun libraryArtistLookup(artists: List<Triple<String, String, Uri?>>): Map<String, String> =
    artists.associate { ArtistNameUtils.normalizeKey(it.first) to it.first }

/**
 * "Artist name" line under an album title. Names that exist in the library are underlined and open
 * that artist's screen; names that don't are shown as plain text.
 */
@Composable
fun AlbumArtistLine(
    names: List<String>,
    libraryArtists: Map<String, String>,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        names.forEachIndexed { index, name ->
            if (index > 0) {
                Text(", ", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            val libraryName = libraryArtists[ArtistNameUtils.normalizeKey(name)]
            if (libraryName != null) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onArtistClick(libraryName) }
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                )
            } else {
                Text(
                    text = name,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- backdrop colour

/** Dominant colour of the first of [models] that loads, animated; [fallback] while loading / on failure. */
@Composable
fun rememberDominantColor(models: List<Any?>, fallback: Color): State<Color> {
    val ctx = LocalContext.current
    val target by produceState(initialValue = fallback, models, fallback) {
        for (m in models) {
            if (m == null) continue
            val c = runCatching { extractDominant(ctx, m) }.getOrNull()
            if (c != null) { value = c; return@produceState }
        }
        value = fallback
    }
    return animateColorAsState(target, tween(500), label = "detailDominantColor")
}

private suspend fun extractDominant(ctx: Context, model: Any): Color? {
    val result = ctx.imageLoader.execute(
        ImageRequest.Builder(ctx).data(model).size(96).allowHardware(false).build()
    )
    val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        val side = 24
        val small = Bitmap.createScaledBitmap(bmp, side, side, true)
        val px = IntArray(side * side)
        small.getPixels(px, 0, side, 0, 0, side, side)
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0
        for (p in px) {
            android.graphics.Color.colorToHSV(p, hsv)
            // favour vivid, reasonably bright pixels over grey / near-black ones
            val vivid = (hsv[1] * hsv[2]).toDouble()
            val w = vivid * vivid + 0.001
            r += android.graphics.Color.red(p) * w
            g += android.graphics.Color.green(p) * w
            b += android.graphics.Color.blue(p) * w
            wSum += w
        }
        val avg = android.graphics.Color.rgb((r / wSum).toInt(), (g / wSum).toInt(), (b / wSum).toInt())
        android.graphics.Color.colorToHSV(avg, hsv)
        // keep it dark & saturated enough for white text and for blending into the app background
        Color.hsv(hsv[0], hsv[1].coerceIn(0.35f, 0.85f), hsv[2].coerceIn(0.28f, 0.55f))
    }
}

/**
 * Full-width colour wash that sits behind the whole screen (status bar, top icons and header art),
 * so the header and top bar share one background. It scrolls away with the list.
 */
@Composable
fun DetailBackdrop(
    color: Color,
    scrollPx: () -> Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(560.dp)
            .graphicsLayer {
                val s = scrollPx()
                translationY = -s
                alpha = (1f - s / (size.height * 0.85f)).coerceIn(0f, 1f)
            }
            .background(
                Brush.verticalGradient(
                    0f to color.copy(alpha = 0.95f),
                    0.45f to color.copy(alpha = 0.55f),
                    1f to Color.Transparent
                )
            )
    )
}
