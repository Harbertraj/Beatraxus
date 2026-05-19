package com.beatflowy.app.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.beatflowy.app.ui.screens.StatItem

@Composable
fun LibraryStatsRow(songCount: Int, albumCount: Int, artistCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(Icons.Rounded.MusicNote, songCount.toString(), "Songs", Color(0xFF1A73E8))
        StatItem(Icons.Rounded.Album, albumCount.toString(), "Albums", Color(0xFF1A73E8))
        StatItem(Icons.Rounded.Person, artistCount.toString(), "Artists", Color(0xFF1A73E8))
    }
}
