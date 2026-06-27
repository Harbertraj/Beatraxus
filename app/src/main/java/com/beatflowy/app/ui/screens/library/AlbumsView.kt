package com.beatflowy.app.ui.screens.library

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.ui.screens.LibraryGridItem

@Composable
fun AlbumsView(
    albums: List<Triple<String, String, Uri?>>,
    gridState: LazyGridState,
    categoryGridColumns: Int,
    onAlbumClick: (String) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
        ) {
            items(albums, key = { it.first + it.second }) { album ->
                Box(Modifier.animateItem()) {
                    LibraryGridItem(album.first, album.second, album.third, onClick = {
                        onAlbumClick(album.first)
                    })
                }
            }
        }
    }
}
