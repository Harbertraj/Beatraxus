package com.beatraxus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beatraxus.app.R
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.theme.BgHighlight

import com.beatraxus.app.utils.ImageUtils

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

import androidx.compose.runtime.remember

@Composable
fun AlbumArtImage(
    song: Song,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    grayscale: Boolean = false
) {
    val context = LocalContext.current
    val defaultArt = ImageUtils.getDefaultAlbumArtRes()
    val model = remember(song.albumArtUri, defaultArt) {
        ImageRequest.Builder(context)
            .data(song.albumArtUri)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .error(defaultArt)
            .fallback(defaultArt)
            .crossfade(200)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = "Album Art",
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(BgHighlight),
        contentScale = ContentScale.Crop,
        colorFilter = if (grayscale) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    )
}
