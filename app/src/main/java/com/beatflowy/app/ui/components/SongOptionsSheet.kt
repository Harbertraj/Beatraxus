package com.beatflowy.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditTags: () -> Unit,
    onSetAsRingtone: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header: Song Info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtImage(song = song, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = song.artist,
                        color = TextMuted,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) Color.Red else Color.White.copy(0.6f)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)

            // Options List
            val options = listOf(
                OptionItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play Next", onPlayNext),
                OptionItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to Queue", onAddToQueue),
                OptionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Playlist", onAddToPlaylist),
                OptionItem(Icons.Rounded.Edit, "Edit Tags", onEditTags),
                OptionItem(Icons.Rounded.Notifications, "Set as Ringtone", onSetAsRingtone),
                OptionItem(Icons.Rounded.Share, "Share", onShare),
                OptionItem(Icons.Rounded.Delete, "Delete from Device", onDelete, Color.Red.copy(0.8f))
            )

            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { option.onClick(); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        option.icon,
                        contentDescription = null,
                        tint = option.tint ?: Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        text = option.label,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private data class OptionItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color? = null
)
