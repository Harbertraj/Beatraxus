package com.beatraxus.app.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beatraxus.app.model.Song
import com.beatraxus.app.repository.lastfm.LastFmAlbum
import com.beatraxus.app.repository.lastfm.LastFmArtistDetail
import com.beatraxus.app.repository.lastfm.LastFmTrack
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.ui.utils.DialogBlurBehind
import com.beatraxus.app.ui.utils.rememberWindowBlurSupported

@Composable
fun SongInfoDialog(
    song: Song,
    lastFmTrackInfo: LastFmTrack? = null,
    lastFmArtistInfo: LastFmArtistDetail? = null,
    lastFmAlbumInfo: LastFmAlbum? = null,
    isLoadingInfo: Boolean = false,
    onDismiss: () -> Unit,
    onOpenInspector: ((Song) -> Unit)? = null,
    /**
     * true  -> the OS blurs the screen behind the dialog window.
     * false -> the caller already blurs its own content (NowPlayingScreen), only the dark dim is removed.
     */
    windowBlur: Boolean = true
) {
    val context = LocalContext.current
    val blurWorks = if (windowBlur) rememberWindowBlurSupported()
    else Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // No dark shade - just blur (or nothing on devices that cannot blur).
        DialogBlurBehind(radiusDp = if (windowBlur) 22 else 0)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (blurWorks) 0f else 0.28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val cardShape = RoundedCornerShape(26.dp)

            // Compact card: ~88% width (max 400dp), wraps content, max ~68% of screen height.
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 400.dp)
                    .heightIn(max = screenHeight * 0.68f)
                    .clip(cardShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1B1C26).copy(alpha = 0.86f),
                                Color(0xFF0E0F15).copy(alpha = 0.90f)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f))
                            )
                        ),
                        cardShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                // Accent glow line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, AccentBlue.copy(alpha = 0.9f), Color.Transparent)
                            )
                        )
                )

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(AccentBlue.copy(alpha = 0.16f), CircleShape)
                                .border(1.dp, AccentBlue.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Song Details",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    if (onOpenInspector != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AccentBlue.copy(alpha = 0.14f))
                                .border(0.5.dp, AccentBlue.copy(alpha = 0.45f), RoundedCornerShape(50))
                                .clickable {
                                    onOpenInspector(song)
                                    onDismiss()
                                }
                                .padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Insights,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Inspect",
                                color = AccentBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(0.08f))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Last.fm Online Information Section
                    if (isLoadingInfo) {
                        Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentBlue, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        }
                    } else if (lastFmTrackInfo != null || lastFmArtistInfo != null || lastFmAlbumInfo != null) {
                        SectionLabel("ONLINE INFORMATION")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                                .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
                                .padding(10.dp),
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
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(0.05f)),
                                contentScale = ContentScale.Crop,
                                error = rememberVectorPainter(Icons.Rounded.Album)
                            )

                            Spacer(Modifier.width(12.dp))

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

                    SectionLabel("FILE METADATA")

                    // One glass block, one compact row per field.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                            .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        MetaRow("Title", song.title)
                        MetaRow("Artist", song.artist)
                        MetaRow("Album", song.album)
                        MetaRow("Genre", song.genre)
                        MetaRow("Duration", formatDuration(song.durationMs))
                        MetaRow("Format", song.format.uppercase())
                        MetaRow("Quality", "${song.sampleRateHz / 1000} kHz | ${song.bitDepth} bit")
                        MetaRow("Location", song.folder, stacked = true, showDivider = false)
                    }
                }

                HorizontalDivider(color = Color.White.copy(0.08f))

                // Dismiss
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(0.10f))
                        .border(0.5.dp, Color.White.copy(0.14f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(0.5f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
private fun MetaRow(
    label: String,
    value: String,
    stacked: Boolean = false,
    showDivider: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Text(label, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    label,
                    color = AccentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(72.dp)
                )
                Text(
                    value,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (showDivider) HorizontalDivider(color = Color.White.copy(0.06f))
    }
}

@Composable
private fun InfoTag(label: String, value: String, maxLines: Int = 2) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.05f), RoundedCornerShape(14.dp))
            .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 12.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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
