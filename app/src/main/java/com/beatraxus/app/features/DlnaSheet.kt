package com.beatraxus.app.features

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Remembers which renderer we are casting to, even after the sheet is closed. */
object DlnaSession {
    var device by mutableStateOf<DlnaDevice?>(null)
    var song by mutableStateOf<Song?>(null)
    var isPlaying by mutableStateOf(false)
}

private val Accent = com.beatraxus.app.ui.theme.AccentBlue

/**
 * DLNA / UPnP sheet.
 *  - "Play on device": send the current song to a TV, speaker or receiver on your Wi-Fi.
 *  - "Browse servers": browse a NAS / media server and play from it on this phone.
 *
 * @param song current song to cast (null = nothing playing)
 * @param positionMs current local position, used as the start point when casting
 * @param onPauseLocal called when casting starts so the phone stops playing
 * @param onPlaySongs called when a song from a media server is chosen
 */
@Composable
fun DlnaSheet(
    song: Song?,
    positionMs: Long,
    onPauseLocal: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var devices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanToken by remember { mutableStateOf(0) }

    LaunchedEffect(scanToken) {
        scanning = true
        devices = DlnaManager.discover(context)
        scanning = false
    }

    com.beatraxus.app.ui.components.InfoStyleDialog(
        title = "DLNA / UPnP",
        icon = Icons.Rounded.Cast,
        onDismiss = onDismiss,
        maxHeightFraction = 0.82f,
        headerTrailing = {
            if (scanning) CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
            else IconButton(onClick = { scanToken++ }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Refresh, "Rescan", tint = Color.White)
            }
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Play on device", "Browse servers").forEachIndexed { i, label ->
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (tab == i) Accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f))
                        .clickable { tab = i }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(label, color = if (tab == i) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
        if (tab == 0) {
            RendererTab(devices.filter { it.isRenderer }, scanning, song, positionMs, onPauseLocal)
        } else {
            ServerTab(devices.filter { it.isServer }, scanning, onPlaySongs, onDismiss)
        }
    }
}

@Composable
private fun RendererTab(
    renderers: List<DlnaDevice>,
    scanning: Boolean,
    song: Song?,
    positionMs: Long,
    onPauseLocal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = DlnaSession.device
    var remotePos by remember { mutableStateOf(0L) }
    var remoteDur by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf(0.5f) }

    LaunchedEffect(active) {
        val d = active ?: return@LaunchedEffect
        while (isActive) {
            DlnaManager.position(d)?.let { remotePos = it.positionMs; remoteDur = it.durationMs }
            delay(1000)
        }
    }

    Column {
        if (active != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Accent.copy(alpha = 0.12f)).padding(14.dp)) {
                Text("Casting to ${active.name}", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(DlnaSession.song?.title ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DlnaSession.song?.artist ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val frac = if (remoteDur > 0) (remotePos.toFloat() / remoteDur).coerceIn(0f, 1f) else 0f
                Slider(
                    value = seeking ?: frac,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        val target = ((seeking ?: frac) * remoteDur).toLong()
                        seeking = null
                        scope.launch { DlnaManager.seek(active, target) }
                    },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        scope.launch {
                            if (DlnaSession.isPlaying) DlnaManager.pause(active) else DlnaManager.play(active)
                            DlnaSession.isPlaying = !DlnaSession.isPlaying
                        }
                    }) {
                        Icon(if (DlnaSession.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                    }
                    IconButton(onClick = {
                        scope.launch { DlnaManager.stop(active); DlnaSession.device = null; DlnaSession.song = null }
                    }) { Icon(Icons.Rounded.Stop, "Stop", tint = Color.White) }
                    if (active.renderingControlUrl != null) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.7f))
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            onValueChangeFinished = { scope.launch { DlnaManager.setVolume(active, (volume * 100).toInt()) } },
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
        }

        if (renderers.isEmpty()) {
            Text(
                if (scanning) "Searching for devices on your Wi-Fi…" else "No players found. Make sure your phone and the device are on the same Wi-Fi, then tap refresh.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        Column {
            renderers.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable {
                            if (song == null) {
                                Toast.makeText(context, "Play a song first", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    val err = DlnaManager.castSong(context, d, song, positionMs)
                                    if (err == null) {
                                        onPauseLocal()
                                        DlnaSession.device = d
                                        DlnaSession.song = song
                                        DlnaSession.isPlaying = true
                                    } else {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Cast, null, tint = if (active?.udn == d.udn) Accent else Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(14.dp))
                    Text(d.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ServerTab(
    servers: List<DlnaDevice>,
    scanning: Boolean,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf<DlnaDevice?>(null) }
    // breadcrumb: (objectId, title)
    var stack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var entries by remember { mutableStateOf<List<DlnaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun load(s: DlnaDevice, id: String) {
        loading = true
        scope.launch {
            entries = DlnaManager.browse(s, id)
            loading = false
        }
    }

    val current = server
    if (current == null) {
        if (servers.isEmpty()) {
            Text(
                if (scanning) "Searching for media servers…" else "No media servers found. Enable DLNA on your NAS / PC (for example Plex, Jellyfin, Synology, Windows Media Player) and tap refresh.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        Column {
            servers.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable { server = d; stack = listOf("0" to d.name); load(d, "0") }
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, null, tint = Accent)
                    Spacer(Modifier.width(14.dp))
                    Text(d.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (stack.size <= 1) { server = null; stack = emptyList(); entries = emptyList() }
                else { stack = stack.dropLast(1); load(current, stack.last().first) }
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text(stack.lastOrNull()?.second ?: current.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        } else {
            val audio = entries.filter { !it.isContainer && it.resUrl != null }
            Column {
                entries.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (e.isContainer) {
                                    stack = stack + (e.id to e.title); load(current, e.id)
                                } else {
                                    val songs = audio.mapNotNull { DlnaManager.entryToSong(current, it) }
                                    val idx = audio.indexOfFirst { it.id == e.id }.coerceAtLeast(0)
                                    if (songs.isNotEmpty()) { onPlaySongs(songs, idx); onDismiss() }
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (e.isContainer) Icons.Rounded.Folder else Icons.Rounded.MusicNote, null,
                            tint = if (e.isContainer) Accent else Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (e.artist.isNotBlank()) Text(e.artist, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
