package com.beatraxus.app.features

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RingVolume
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.theme.BgBase
import com.beatraxus.app.ui.theme.PremiumTeal
import com.beatraxus.app.ui.theme.TextMuted
import com.beatraxus.app.ui.theme.TextPrimary
import com.beatraxus.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Lengths offered as chips. "Full song" is always offered as well; chips longer than the song are hidden. */
private val CUT_LENGTHS_SEC = listOf(15, 30, 40, 90)

/** Shortest cut the user can drag the window down to. */
private const val MIN_CUT_MS = 1000L

/**
 * Ringtone cutter (Instagram audio-picker style): the whole song as a waveform, a window you drag to choose
 * the part, a looping preview of just that part, then Set as ringtone / notification / alarm.
 */
@Composable
fun RingtoneCutterScreen(song: Song, onDismiss: () -> Unit, onPauseLocalPlayback: () -> Unit = {}) {
    if (song.isCloud()) {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            Toast.makeText(context, "Download the song first. Cloud songs can't be used as a ringtone.", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    } else {
        CutterContent(song, onDismiss, onPauseLocalPlayback)
    }
}

@Composable
private fun CutterContent(song: Song, onDismiss: () -> Unit, onPauseLocalPlayback: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val accent = PremiumTeal
    val onAccent = if (accent.luminance() > 0.35f) Color.Black else Color.White

    var totalMs by remember(song.id) { mutableLongStateOf(song.durationMs.coerceAtLeast(0L)) }
    var lenMs by remember(song.id) {
        mutableLongStateOf(if (song.durationMs in 1L..30_000L) song.durationMs else 30_000L)
    }
    var startMs by remember(song.id) { mutableLongStateOf(0L) }
    var userMoved by remember(song.id) { mutableStateOf(false) }
    var fade by remember { mutableStateOf(true) }
    var type by remember { mutableIntStateOf(RingtoneManager.TYPE_RINGTONE) }
    var busy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ExportFormat.MP3) }

    var peaks by remember(song.id) { mutableStateOf<FloatArray?>(null) }
    var playerReady by remember(song.id) { mutableStateOf(false) }
    var playing by remember(song.id) { mutableStateOf(false) }
    var playPosMs by remember(song.id) { mutableLongStateOf(0L) }

    val player = remember(song.id) {
        ClipPreviewPlayer(
            context = context,
            uri = song.uri,
            onPrepared = { durationMs ->
                playerReady = true
                if (totalMs <= 0L) totalMs = durationMs
            },
            onError = {
                playing = false
                playerReady = false
                Toast.makeText(context, "Can't preview this song, but you can still cut it.", Toast.LENGTH_SHORT).show()
            }
        )
    }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(song.id) { peaks = RingtoneWaveform.load(context, song.uri) }

    // Start the window on the middle part of the song (usually where the hook is) until the user moves it.
    LaunchedEffect(totalMs, lenMs) {
        if (totalMs > 0L && lenMs > totalMs) lenMs = totalMs
        if (!userMoved && totalMs > 0L) {
            val latest = max(0L, totalMs - lenMs)
            startMs = ((totalMs * 3 / 10).coerceAtMost(latest)) / 100L * 100L
        }
    }

    // Looping preview of the chosen part. Restarts (after a short pause, so dragging stays smooth) when the window changes.
    LaunchedEffect(playing, startMs, lenMs, playerReady) {
        if (!playing || !playerReady) {
            player.pause()
            return@LaunchedEffect
        }
        delay(120)
        player.playFrom(startMs)
        while (true) {
            val p = player.position()
            playPosMs = (p - startMs).coerceIn(0L, lenMs)
            if (p >= startMs + lenMs) player.playFrom(startMs)
            delay(40)
        }
    }

    val isWhole = totalMs > 0L && lenMs >= totalMs

    fun setLength(newLen: Long) {
        val l = newLen.coerceIn(1000L, max(1000L, totalMs))
        lenMs = l
        startMs = startMs.coerceIn(0L, max(0L, totalMs - l))
        userMoved = true
        playPosMs = 0L
    }

    fun apply() {
        if (busy || totalMs <= 0L) return
        busy = true
        playing = false
        scope.launch {
            val r = if (isWhole && !fade) {
                RingtoneHelper.setAs(context, song, type)
            } else {
                val from = if (isWhole) 0L else startMs
                val len = if (isWhole) totalMs else lenMs
                RingtoneHelper.setClipAs(context, song, from, len, fade, type)
            }
            when (r) {
                is RingtoneHelper.Result.Success -> {
                    Toast.makeText(context, "${typeLabel(type)} set: ${song.title}", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                is RingtoneHelper.Result.NeedsWriteSettings -> {
                    Toast.makeText(context, "Allow \"Modify system settings\" for Beatraxus, then try again", Toast.LENGTH_LONG).show()
                    RingtoneHelper.openWriteSettings(context)
                    onDismiss()
                }
                is RingtoneHelper.Result.Error -> {
                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                    busy = false
                }
            }
        }
    }

    fun saveCopy() {
        if (busy || totalMs <= 0L) return
        busy = true
        exporting = true
        playing = false
        scope.launch {
            val from = if (isWhole) 0L else startMs
            val len = if (isWhole) totalMs else lenMs
            when (val r = RingtoneHelper.saveClip(context, song, from, len, fade, exportFormat)) {
                is RingtoneHelper.SaveResult.Saved ->
                    Toast.makeText(context, "Saved to Music/${RingtoneHelper.EXPORT_FOLDER}: ${r.fileName}", Toast.LENGTH_LONG).show()
                is RingtoneHelper.SaveResult.Error ->
                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
            }
            exporting = false
            busy = false
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = BgBase) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                    Text("Cut ringtone", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { apply() }, enabled = !busy && totalMs > 0L) {
                        Text("Done", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TextPrimary.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextSecondary)
                        if (song.albumArtUri != null) {
                            AsyncImage(
                                model = song.albumArtUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(song.title, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${song.artist} · ${formatMs(totalMs)}",
                            color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isWhole) "Full song" else "${formatMs(startMs)} – ${formatMs(startMs + lenMs)}",
                        color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (isWhole) "The whole song will be used" else "${lenMs / 1000} seconds selected",
                        color = TextSecondary, fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (totalMs > 0L) {
                    CutterWaveform(
                        peaks = peaks,
                        totalMs = totalMs,
                        startMs = startMs,
                        lenMs = lenMs,
                        progress = if (playing) playPosMs.toFloat() / lenMs.toFloat() else -1f,
                        accent = accent,
                        onRangeChange = { s, l ->
                            startMs = s
                            lenMs = l
                            userMoved = true
                            playPosMs = 0L
                        }
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0:00", color = TextMuted, fontSize = 12.sp)
                    Text(
                        if (peaks == null) "Reading waveform…" else if (isWhole) "Full song selected" else "Drag to move · pull edges to resize",
                        color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                    Text(formatMs(totalMs), color = TextMuted, fontSize = 12.sp)
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(TextPrimary)
                            .clickable(enabled = !busy) {
                                if (!playing) onPauseLocalPlayback()
                                playing = !playing
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause preview" else "Play preview",
                            tint = BgBase,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (totalMs > 0L) {
                            CUT_LENGTHS_SEC.filter { it * 1000L < totalMs }.forEach { sec ->
                                LengthChip("${sec}s", selected = !isWhole && lenMs == sec * 1000L) { setLength(sec * 1000L) }
                            }
                            LengthChip("Full song", selected = isWhole) { setLength(totalMs) }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TextPrimary.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fade in and out", color = TextPrimary)
                        Text("Softens the start and end of the tone", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = fade,
                        onCheckedChange = { fade = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = onAccent)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text("Use this cut as", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeSegment(Icons.Rounded.RingVolume, "Ringtone", type == RingtoneManager.TYPE_RINGTONE, accent, onAccent, Modifier.weight(1f)) {
                        type = RingtoneManager.TYPE_RINGTONE
                    }
                    TypeSegment(Icons.Rounded.Notifications, "Notification", type == RingtoneManager.TYPE_NOTIFICATION, accent, onAccent, Modifier.weight(1f)) {
                        type = RingtoneManager.TYPE_NOTIFICATION
                    }
                    TypeSegment(Icons.Rounded.Alarm, "Alarm", type == RingtoneManager.TYPE_ALARM, accent, onAccent, Modifier.weight(1f)) {
                        type = RingtoneManager.TYPE_ALARM
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { apply() },
                    enabled = !busy && totalMs > 0L,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = onAccent,
                        disabledContainerColor = accent.copy(alpha = 0.6f),
                        disabledContentColor = onAccent.copy(alpha = 0.7f)
                    )
                ) {
                    if (busy && !exporting) {
                        CircularProgressIndicator(color = onAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Saving…", color = onAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Text("Set as ${typeLabel(type).lowercase()}", color = onAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text("Save as", color = TextSecondary, fontSize = 12.sp)
                Text("Keep this cut as an audio file in Music/${RingtoneHelper.EXPORT_FOLDER}", color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportFormat.values().forEach { f ->
                        LengthChip(f.label, selected = exportFormat == f) { if (!busy) exportFormat = f }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { saveCopy() },
                    enabled = !busy && totalMs > 0L,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TextPrimary.copy(alpha = 0.10f),
                        contentColor = TextPrimary,
                        disabledContainerColor = TextPrimary.copy(alpha = 0.06f),
                        disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                    )
                ) {
                    if (exporting) {
                        CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Saving…", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Text("Save as ${exportFormat.label}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun typeLabel(type: Int): String = when (type) {
    RingtoneManager.TYPE_NOTIFICATION -> "Notification"
    RingtoneManager.TYPE_ALARM -> "Alarm"
    else -> "Ringtone"
}

private fun formatMs(ms: Long): String {
    val s = (ms.coerceAtLeast(0L)) / 1000L
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
private fun LengthChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) TextPrimary else TextPrimary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) BgBase else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun TypeSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    onAccent: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else TextPrimary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) onAccent else TextSecondary, modifier = Modifier.size(20.dp))
        Text(label, color = if (selected) onAccent else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Full-song waveform with a selection window.
 * - Drag the left or right edge handle to set a custom length.
 * - Tap or drag anywhere else to move the window (keeping its length).
 */
@Composable
private fun CutterWaveform(
    peaks: FloatArray?,
    totalMs: Long,
    startMs: Long,
    lenMs: Long,
    progress: Float,
    accent: Color,
    onRangeChange: (startMs: Long, lenMs: Long) -> Unit
) {
    val onChange by rememberUpdatedState(onRangeChange)
    // The gesture below must not restart while the window is being resized, so it reads the
    // latest window through these instead of using startMs/lenMs as pointerInput keys.
    val curStart by rememberUpdatedState(startMs)
    val curLen by rememberUpdatedState(lenMs)
    val placeholder = remember {
        val r = Random(7)
        FloatArray(160) { 0.25f + r.nextFloat() * 0.5f }
    }
    val ink = TextPrimary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .pointerInput(totalMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (totalMs <= 0L || size.width <= 0) return@awaitEachGesture

                    val w = size.width.toFloat()
                    val s0 = curStart
                    val e0 = s0 + curLen
                    val winL = w * s0.toFloat() / totalMs.toFloat()
                    val winR = w * e0.toFloat() / totalMs.toFloat()
                    val x0 = down.position.x

                    // Which part of the window was grabbed. The grab zone sticks out a little past each edge so
                    // the thin handle is easy to catch, and is limited inside so a short window can still be moved.
                    val inner = min(24.dp.toPx(), (winR - winL) / 3f)
                    val outer = 20.dp.toPx()
                    val nearLeft = x0 >= winL - outer && x0 <= winL + inner
                    val nearRight = x0 >= winR - inner && x0 <= winR + outer
                    val mode = when {
                        nearLeft && nearRight -> if (abs(x0 - winL) <= abs(x0 - winR)) GRAB_LEFT else GRAB_RIGHT
                        nearLeft -> GRAB_LEFT
                        nearRight -> GRAB_RIGHT
                        else -> GRAB_MOVE
                    }
                    // Keep the finger's offset from the edge so the edge doesn't jump to the touch point.
                    val grabOffset = when (mode) {
                        GRAB_LEFT -> x0 - winL
                        GRAB_RIGHT -> x0 - winR
                        else -> 0f
                    }

                    fun msAt(x: Float): Long =
                        ((x / w).coerceIn(0f, 1f) * totalMs).toLong() / 100L * 100L

                    fun update(x: Float) {
                        when (mode) {
                            GRAB_LEFT -> {
                                val maxStart = max(0L, e0 - MIN_CUT_MS)
                                val ns = msAt(x - grabOffset).coerceIn(0L, maxStart)
                                onChange(ns, e0 - ns)
                            }
                            GRAB_RIGHT -> {
                                val minEnd = min(totalMs, s0 + MIN_CUT_MS)
                                val ne = msAt(x - grabOffset).coerceIn(minEnd, totalMs)
                                onChange(s0, ne - s0)
                            }
                            else -> {
                                val len = curLen
                                val center = (x / w).coerceIn(0f, 1f) * totalMs
                                val s = (center - len / 2f).toLong().coerceIn(0L, max(0L, totalMs - len))
                                onChange(s / 100L * 100L, len)
                            }
                        }
                    }

                    // A tap on an edge just grabs it; a tap elsewhere still jumps the window there.
                    if (mode == GRAB_MOVE) update(x0)
                    drag(down.id) { change ->
                        update(change.position.x)
                        change.consume()
                    }
                }
            }
    ) {
        val src = peaks ?: placeholder
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val count = (size.width / (barW + gap)).toInt().coerceAtLeast(1)
        val vPad = 8.dp.toPx()
        val winL = size.width * startMs.toFloat() / totalMs.toFloat()
        val winR = size.width * (startMs + lenMs).toFloat() / totalMs.toFloat()
        val dim = if (peaks == null) 0.15f else 0.28f

        for (i in 0 until count) {
            val x = i * (barW + gap)
            val cx = x + barW / 2f
            val a = (i * src.size / count).coerceIn(0, src.size - 1)
            val b = ((i + 1) * src.size / count).coerceIn(a + 1, src.size)
            var p = 0f
            for (k in a until b) if (src[k] > p) p = src[k]
            val h = (size.height - 2 * vPad) * (0.12f + 0.88f * p)
            val inWindow = cx >= winL && cx <= winR
            val played = inWindow && progress >= 0f && cx < winL + (winR - winL) * progress
            val color = when {
                played -> accent
                inWindow -> ink
                else -> ink.copy(alpha = dim)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }

        val stroke = 2.dp.toPx()
        val width = max(winR - winL, 8.dp.toPx())
        drawRoundRect(
            color = ink,
            topLeft = Offset(winL + stroke / 2f, stroke / 2f),
            size = Size(width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = stroke)
        )
        val handleH = 34.dp.toPx()
        val handleW = 5.dp.toPx()
        val handleTop = (size.height - handleH) / 2f
        drawRoundRect(ink, Offset(winL, handleTop), Size(handleW, handleH), CornerRadius(handleW / 2f))
        drawRoundRect(ink, Offset(winL + width - handleW, handleTop), Size(handleW, handleH), CornerRadius(handleW / 2f))
    }
}

private const val GRAB_MOVE = 0
private const val GRAB_LEFT = 1
private const val GRAB_RIGHT = 2

/** Thin MediaPlayer wrapper that plays only [playFrom] onwards; the screen loops it inside the chosen window. */
private class ClipPreviewPlayer(
    context: Context,
    uri: Uri,
    onPrepared: (Long) -> Unit,
    onError: () -> Unit
) {
    private val mp = MediaPlayer()
    @Volatile private var ready = false
    @Volatile private var released = false

    init {
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.setOnPreparedListener {
                ready = true
                onPrepared(it.duration.toLong().coerceAtLeast(0L))
            }
            mp.setOnErrorListener { _, _, _ ->
                ready = false
                onError()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post { onError() }
        }
    }

    fun playFrom(ms: Long) {
        if (!ready || released) return
        runCatching {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
            mp.start()
        }
    }

    fun pause() {
        if (!ready || released) return
        runCatching { if (mp.isPlaying) mp.pause() }
    }

    fun position(): Long {
        if (!ready || released) return 0L
        return runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
    }

    fun release() {
        released = true
        ready = false
        runCatching { mp.release() }
    }
}

/** Real waveform of a song: ffmpeg decodes it to 2 kHz mono PCM, then the loudest sample of each slice is kept. */
internal object RingtoneWaveform {

    suspend fun load(context: Context, uri: Uri, bars: Int = 160): FloatArray? = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "ringtone_wave_${System.nanoTime()}.pcm")
        try {
            val session = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y", "-v", "error",
                    "-i", FFmpegKitConfig.getSafParameterForRead(context, uri),
                    "-vn", "-ac", "1", "-ar", "2000", "-f", "s16le",
                    tmp.absolutePath
                )
            )
            if (!ReturnCode.isSuccess(session.returnCode) || tmp.length() < 4L) return@withContext null

            val samples = ByteBuffer.wrap(tmp.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val total = samples.remaining()
            if (total <= 0) return@withContext null

            val peaks = FloatArray(bars)
            for (i in 0 until total) {
                val bucket = (i.toLong() * bars / total).toInt().coerceAtMost(bars - 1)
                val v = abs(samples.get(i).toInt()) / 32768f
                if (v > peaks[bucket]) peaks[bucket] = v
            }
            val top = peaks.max()
            if (top <= 0f) return@withContext null
            for (i in peaks.indices) peaks[i] = sqrt(peaks[i] / top)
            peaks
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }
}
