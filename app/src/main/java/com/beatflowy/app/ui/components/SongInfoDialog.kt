package com.beatflowy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.beatflowy.app.model.Song
import com.beatflowy.app.repository.lastfm.LastFmRepository
import com.beatflowy.app.repository.lastfm.LastFmTrack
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.ui.theme.BgDeep
import kotlinx.coroutines.launch

@Composable
fun SongInfoDialog(
    song: Song,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lastFmRepository = remember { LastFmRepository(context) }
    var lastFmTrackInfo by remember { mutableStateOf<LastFmTrack?>(null) }
    var isLoadingInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(song.id) {
        isLoadingInfo = true
        scope.launch {
            lastFmTrackInfo = lastFmRepository.getTrackInfo(song.artist, song.title)
            isLoadingInfo = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(28.dp),
            color = BgDeep,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    "Song Details",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Last.fm Thumbnail and Online Info
                    if (isLoadingInfo) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentBlue)
                        }
                    } else if (lastFmTrackInfo != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val onlineArt = lastFmTrackInfo?.album?.image?.lastOrNull { it.url.isNotEmpty() }?.url 
                                ?: lastFmTrackInfo?.artist?.image?.lastOrNull { it.url.isNotEmpty() }?.url
                            
                            AsyncImage(
                                model = onlineArt,
                                contentDescription = "Last.fm Artwork",
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.DarkGray),
                                contentScale = ContentScale.Crop
                            )
                            
                            Spacer(Modifier.width(16.dp))
                            
                            Column {
                                Text("Last.fm Tags", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                val tags = lastFmTrackInfo?.toptags?.tag?.take(5)?.joinToString(", ") { it.name }
                                Text(tags ?: "No tags found", color = Color.White, fontSize = 14.sp)
                                
                                Spacer(Modifier.height(4.dp))
                                
                                Text("Listeners", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(lastFmTrackInfo?.listeners ?: "0", color = Color.White, fontSize = 14.sp)
                            }
                        }

                        lastFmTrackInfo?.wiki?.summary?.let { summary ->
                            InfoTag("Summary", summary.replace(Regex("<[^>]*>"), ""))
                        }
                    }

                    Text("File Metadata", color = Color.White.copy(0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    
                    InfoTag("Title", song.title)
                    InfoTag("Artist", song.artist)
                    InfoTag("Album", song.album)
                    InfoTag("Genre", song.genre)
                    InfoTag("Duration", formatDuration(song.durationMs))
                    InfoTag("Format", song.format.uppercase())
                    InfoTag("Quality", "${song.sampleRateHz / 1000} kHz | ${song.bitDepth} bit")
                    InfoTag("Location", song.folder)
                }

                Spacer(Modifier.height(16.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold)
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
        Text(value, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatDuration(ms: Long): String {
    val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}
