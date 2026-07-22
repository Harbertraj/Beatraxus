package com.beatraxus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import coil.request.ImageRequest
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.beatraxus.app.model.Song
import com.beatraxus.app.repository.lastfm.LastFmRepository
import com.beatraxus.app.repository.lastfm.LastFmTrack
import com.beatraxus.app.repository.lastfm.LastFmArtistDetail
import com.beatraxus.app.repository.lastfm.LastFmAlbum
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.ui.theme.BgDeep
import kotlinx.coroutines.launch

@Composable
fun SongInfoDialog(
    song: Song,
    lastFmTrackInfo: LastFmTrack? = null,
    lastFmArtistInfo: LastFmArtistDetail? = null,
    lastFmAlbumInfo: LastFmAlbum? = null,
    isLoadingInfo: Boolean = false,
    onDismiss: () -> Unit,
    onOpenInspector: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Song Details",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (onOpenInspector != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AccentBlue.copy(alpha = 0.12f))
                                .clickable {
                                    onOpenInspector(song)
                                    onDismiss()
                                }
                                .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Rounded.Insights,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Inspect",
                                color = AccentBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Last.fm Online Information Section
                    if (isLoadingInfo) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentBlue)
                        }
                    } else if (lastFmTrackInfo != null || lastFmArtistInfo != null || lastFmAlbumInfo != null) {
                        Text("Online Information", color = Color.White.copy(0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val onlineArt = remember(lastFmAlbumInfo, lastFmTrackInfo, lastFmArtistInfo) {
                                // Priority: 1. Full Album Info, 2. Track's Album, 3. Track directly, 4. Artist's Track info, 5. Full Artist Bio
                                lastFmAlbumInfo?.image?.lastOrNull { it.url.isNotBlank() }?.url 
                                    ?: lastFmTrackInfo?.album?.image?.lastOrNull { it.url.isNotBlank() }?.url
                                    ?: lastFmTrackInfo?.image?.lastOrNull { it.url.isNotBlank() }?.url
                                    ?: lastFmTrackInfo?.artist?.image?.lastOrNull { it.url.isNotBlank() }?.url
                                    ?: lastFmArtistInfo?.image?.lastOrNull { it.url.isNotBlank() }?.url
                            }
                            
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(onlineArt)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Online Artwork",
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(0.05f)),
                                contentScale = ContentScale.Crop,
                                error = rememberVectorPainter(androidx.compose.material.icons.Icons.Rounded.Album)
                            )
                            
                            Spacer(Modifier.width(16.dp))
                            
                            Column(Modifier.weight(1f)) {
                                if (lastFmTrackInfo?.listeners != null) {
                                    OnlineStat("Listeners", lastFmTrackInfo?.listeners ?: "0")
                                }
                                if (lastFmTrackInfo?.playcount != null) {
                                    OnlineStat("Playcount", lastFmTrackInfo?.playcount ?: "0")
                                }
                                if (lastFmTrackInfo?.userplaycount != null && lastFmTrackInfo?.userplaycount != "0") {
                                    OnlineStat("Your Plays", lastFmTrackInfo?.userplaycount ?: "0")
                                }
                                if (lastFmArtistInfo?.stats?.listeners != null) {
                                    OnlineStat("Artist Listeners", lastFmArtistInfo?.stats?.listeners ?: "0")
                                }
                            }
                        }

                        // Online Tags
                        val allTags = mutableSetOf<String>()
                        lastFmTrackInfo?.toptags?.tag?.map { it.name }?.let { allTags.addAll(it) }
                        lastFmArtistInfo?.tags?.tag?.map { it.name }?.let { allTags.addAll(it) }
                        
                        if (allTags.isNotEmpty()) {
                            InfoTag("Online Tags", allTags.take(12).joinToString(", "))
                        }

                        // Online Wiki / Bio
                        val wikiContent = lastFmTrackInfo?.wiki?.content 
                            ?: lastFmTrackInfo?.wiki?.summary
                            ?: lastFmArtistInfo?.bio?.content
                            ?: lastFmArtistInfo?.bio?.summary
                        
                        if (wikiContent != null) {
                            InfoTag("Online Bio/Summary", wikiContent.replace(Regex("<[^>]*>"), ""), maxLines = 10)
                        }

                        // Similar Artists
                        val similar = lastFmArtistInfo?.similar?.artist?.map { it.name }
                        if (!similar.isNullOrEmpty()) {
                            InfoTag("Similar Artists", similar.take(6).joinToString(", "))
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
private fun InfoTag(label: String, value: String, maxLines: Int = 2) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.05f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 14.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OnlineStat(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatDuration(ms: Long): String {
    val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}
