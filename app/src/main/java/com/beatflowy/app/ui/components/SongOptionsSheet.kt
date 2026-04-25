package com.beatflowy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatflowy.app.model.Song
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.ui.theme.BgDeep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    song: Song,
    currentPlayingSong: Song?,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onGoToArtist: () -> Unit,
    onGoToAlbum: () -> Unit,
    onGoToFolder: () -> Unit,
    onGoToGenre: () -> Unit
) {
    var showInfoOverlay by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.8f),
        dragHandle = { 
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) 
        },
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        val contentColor = Color.White
        val subContentColor = contentColor.copy(alpha = 0.7f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
        ) {
            // Main Screen Background Logic (Blurred Art or Deep Background)
            if (currentPlayingSong != null) {
                AsyncImage(
                    model = currentPlayingSong.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .blur(50.dp)
                        .graphicsLayer(alpha = 0.5f),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(0.4f),
                                    Color.Black.copy(0.7f)
                                )
                            )
                        )
                )
            } else {
                Box(Modifier.matchParentSize().background(BgDeep))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header: Song Info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtImage(song = song, size = 56.dp, cornerRadius = 12.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = contentColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            color = subContentColor,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    
                    // Favorite Button
                    IconButton(onClick = { 
                        onToggleFavorite()
                    }) {
                        Icon(
                            if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) AccentBlue else contentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Options Grid
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Row 1: Playlist & Play Next
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OptionGridItem(OptionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", {}), contentColor, Modifier.weight(1f)) {
                            onAddToPlaylist()
                            onDismiss()
                        }
                        OptionGridItem(OptionItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play Next", {}), contentColor, Modifier.weight(1f)) {
                            onPlayNext()
                            onDismiss()
                        }
                    }

                    // Row 2: Add to Queue & Info
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OptionGridItem(OptionItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to Queue", {}), contentColor, Modifier.weight(1f)) {
                            onAddToQueue()
                            onDismiss()
                        }
                        OptionGridItem(OptionItem(Icons.Rounded.Info, "Info/Tags", {}), contentColor, Modifier.weight(1f)) {
                            showInfoOverlay = true
                        }
                    }

                    // Row 3: Artist & Album
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OptionGridItem(OptionItem(Icons.Rounded.Person, "Artist", {}), contentColor, Modifier.weight(1f)) {
                            onGoToArtist()
                            onDismiss()
                        }
                        OptionGridItem(OptionItem(Icons.Rounded.Album, "Album", {}), contentColor, Modifier.weight(1f)) {
                            onGoToAlbum()
                            onDismiss()
                        }
                    }

                    // Row 4: Folder
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OptionGridItem(OptionItem(Icons.Rounded.FolderOpen, "Folder", {}), contentColor, Modifier.weight(1f)) {
                            onGoToFolder()
                            onDismiss()
                        }
                        Spacer(Modifier.weight(1f))
                    }

                    // Row 5: Genre & Delete
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OptionGridItem(OptionItem(Icons.Rounded.Headphones, "Genre", {}), contentColor, Modifier.weight(1f)) {
                            onGoToGenre()
                            onDismiss()
                        }
                        OptionGridItem(OptionItem(Icons.Rounded.Delete, "Delete", {}, tint = Color(0xFFFF5252)), contentColor, Modifier.weight(1f)) {
                            onDelete()
                            onDismiss()
                        }
                    }
                }
            }

            // Info Overlay
            if (showInfoOverlay) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showInfoOverlay = false }) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .wrapContentHeight(),
                                shape = RoundedCornerShape(24.dp),
                                color = Color.Black
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Song Info", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                                    
                                    InfoTag("Title", song.title)
                                    InfoTag("Artist", song.artist)
                                    InfoTag("Album", song.album)
                                    InfoTag("Duration", formatDuration(song.durationMs))
                                    InfoTag("Format", song.format.uppercase())
                                    InfoTag("Quality", "${song.sampleRateHz / 1000} kHz | ${song.bitDepth} bit")
                                    InfoTag("Location", song.folder)
                                    InfoTag("Genre", song.genre)

                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { showInfoOverlay = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Close", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoTag(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.05f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun OptionGridItem(
    option: OptionItem,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            option.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = option.label,
            color = contentColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}


private fun formatDuration(ms: Long): String {
    val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}


private data class OptionItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color? = null
)
