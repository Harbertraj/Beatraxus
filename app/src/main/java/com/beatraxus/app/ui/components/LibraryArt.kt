package com.beatraxus.app.ui.components

import android.net.Uri
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Cover-art helpers for the library tile grids (albums, artists, folders, years).
 *
 * Why this exists: the "All songs" list has no pictures and scrolls smoothly, but every tile of the category
 * grids has to decode and upload a cover. Letting Coil work the size out from layout, fading every image in and
 * only starting a decode when a tile scrolls into view made fast scrolling drop frames. Here every tile of a
 * grid asks for the SAME fixed size (so the memory-cache key is predictable), without a fade, and the covers
 * just beyond the visible rows are decoded ahead of time ([PrefetchTileArt]) so they are already in memory
 * when the tile appears.
 */

/** Pixel size (square) cover art should be decoded at for a grid with [columns] columns. */
@Composable
fun rememberTileArtPx(columns: Int): Int {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current.density
    return remember(columns, screenWidthDp, density) {
        val cols = columns.coerceAtLeast(1)
        val tileDp = (screenWidthDp - 32f - 8f * (cols - 1)) / cols
        val px = (tileDp * density).toInt()
        // round up to a multiple of 64 so small layout differences do not change the cache key
        (((px + 63) / 64) * 64).coerceIn(128, 1024)
    }
}

/** One cover tile image: fixed decode size, no cross-fade, memory + disk cache. */
@Composable
fun TileArtImage(
    uri: Uri?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = remember(uri, sizePx) {
        tileRequest(context, uri, sizePx)
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale
    )
}

private fun tileRequest(context: android.content.Context, uri: Uri?, sizePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(sizePx)
        .crossfade(false)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

/**
 * Decodes the covers of the next few rows ahead of the scroll position into Coil's memory cache.
 * [uris] is indexed like the grid's items. Cheap when nothing changes: it only reacts to the last visible index.
 */
@Composable
fun PrefetchTileArt(state: LazyGridState, uris: List<Uri?>, sizePx: Int, ahead: Int = 12) {
    val context = LocalContext.current
    LaunchedEffect(state, uris, sizePx) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                val from = (last + 1).coerceAtLeast(0)
                val to = (from + ahead).coerceAtMost(uris.size)
                for (i in from until to) {
                    val u = uris[i] ?: continue
                    context.imageLoader.enqueue(tileRequest(context, u, sizePx))
                }
            }
    }
}
