package com.beatflowy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.theme.BgHighlight

@Composable
fun AlbumArtImage(
    song: Song,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    AsyncImage(
        model = song.albumArtUri,
        contentDescription = "Album Art",
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(BgHighlight),
        contentScale = ContentScale.Crop,
        error = ColorPainter(getPlaceholderColor(song.id.hashCode())),
        fallback = ColorPainter(getPlaceholderColor(song.id.hashCode())),
        onLoading = { /* Potential logging */ },
        onSuccess = { /* Potential logging */ },
        onError = { /* Potential logging */ }
    )
}

private fun getPlaceholderColor(seed: Int): Color {
    val hue = (seed.and(0xFF) / 255f) * 360f
    return Color.hsv(hue, 0.6f, 0.5f)
}
