package com.beatflowy.app.ui.components

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
import coil.compose.AsyncImage
import com.beatflowy.app.R
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
        contentScale = ContentScale.Crop
    )
}
