package com.beatraxus.app.features

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.theme.BgBase
import com.beatraxus.app.ui.theme.PremiumTeal
import com.beatraxus.app.ui.theme.TextMuted
import com.beatraxus.app.ui.theme.TextPrimary
import com.beatraxus.app.ui.theme.TextSecondary
import com.beatraxus.app.ui.theme.bgTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/** Lengths offered as chips. "Full song" is always offered as well; chips longer than the song are hidden. */
private val CUT_LENGTHS_SEC = listOf(15, 30, 40, 90)

/** Shortest cut the user can drag the window down to. */
private const val MIN_CUT_MS = 1000L

/** Zoom of the scrolling waveform: how wide one second is. Short songs are stretched to fill the width instead. */
private const val PX_PER_SEC_DP = 64f

/** Dragging snaps the window edges to this many milliseconds. Fine-tune buttons go down to the same step. */
private const val SNAP_MS = 10L

/** Waveform detail: peaks kept per second of audio (the scrolling view needs far more than the old 160 bars). */
private const val PEAKS_PER_SEC = 25

/** Fine-tune steps (ms) shown next to Start and End. */
private val NUDGE_STEPS_MS = listOf(-100L, -10L, 10L, 100L)

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

/** Near-black base of the screen: the same colour the Music Detail Inspector uses. */
private val CutterBg: Color get() = bgTone(0xFF07060B)

/**
 * Soft, heavily blurred album-art glow at the top of the screen that fades into [CutterBg]. Same look as the
 * Music Detail Inspector background, so the "Set as" screen matches it.
 */
@Composable
private fun BoxScope.CutterAmbientBackdrop(song: Song) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(song.albumArtUri)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .size(256, 256)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .align(Alignment.TopCenter)
            .blur(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 60.dp else 30.dp)
            .background(Color.Black.copy(alpha = 0.55f))
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .align(Alignment.TopCenter)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.45f),
                        CutterBg.copy(alpha = 0.75f),
                        CutterBg
                    )
                )
            )
    )
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

    // Asks the waveform to scroll a time into view (used by the fine-tune buttons when the edge is off screen).
    var revealMs by remember(song.id) { mutableLongStateOf(0L) }
    var revealTick by remember(song.id) { mutableIntStateOf(0) }

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

    LaunchedEffect(song.id) {
        val bars = if (song.durationMs > 0L) (song.durationMs / 1000L * PEAKS_PER_SEC).toInt() else 4000
        peaks = RingtoneWaveform.load(context, song.uri, bars.coerceIn(160, 30_000))
    }

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

    fun nudgeStart(deltaMs: Long) {
        if (busy || totalMs <= 0L) return
        val end = startMs + lenMs
        val ns = (startMs + deltaMs).coerceIn(0L, max(0L, end - MIN_CUT_MS))
        startMs = ns
        lenMs = end - ns
        userMoved = true
        playPosMs = 0L
        revealMs = ns
        revealTick++
    }

    fun nudgeEnd(deltaMs: Long) {
        if (busy || totalMs <= 0L) return
        val ne = (startMs + lenMs + deltaMs).coerceIn(min(totalMs, startMs + MIN_CUT_MS), totalMs)
        lenMs = ne - startMs
        userMoved = true
        playPosMs = 0L
        revealMs = ne
        revealTick++
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
        Surface(modifier = Modifier.fillMaxSize(), color = CutterBg) {
            Box(modifier = Modifier.fillMaxSize()) {
                CutterAmbientBackdrop(song)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
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
                            if (isWhole) AnnotatedString("Full song") else buildAnnotatedString {
                                append(preciseTime(startMs, TextPrimary, TextSecondary))
                                withStyle(SpanStyle(color = TextMuted)) { append("  –  ") }
                                append(preciseTime(startMs + lenMs, TextPrimary, TextSecondary))
                            },
                            color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                            style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1
                        )
                        Text(
                            if (isWhole) "The whole song will be used" else "${formatLength(lenMs)} selected",
                            color = TextSecondary, fontSize = 13.sp,
                            style = TextStyle(fontFeatureSettings = "tnum")
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
                            onAccent = onAccent,
                            revealMs = revealMs,
                            revealTick = revealTick,
                            onRangeChange = { s, l ->
                                startMs = s
                                lenMs = l
                                userMoved = true
                                playPosMs = 0L
                            }
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(168.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            peaks == null -> "Reading waveform…"
                            isWhole -> "Full song selected · swipe outside the frame to scroll"
                            else -> "Drag frame to move · pull edges to resize · swipe outside to scroll"
                        },
                        color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

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

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TextPrimary.copy(alpha = 0.06f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fine tune", color = TextPrimary, modifier = Modifier.weight(1f))
                            Text("ms steps · hold to repeat", color = TextMuted, fontSize = 12.sp)
                        }
                        FineTuneRow("Start", startMs, enabled = !busy && totalMs > 0L) { nudgeStart(it) }
                        FineTuneRow("End", startMs + lenMs, enabled = !busy && totalMs > 0L) { nudgeEnd(it) }
                    }

                    Spacer(Modifier.height(12.dp))

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

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(TextPrimary.copy(alpha = 0.06f))
                            .padding(14.dp)
                    ) {
                        Text("Save as", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Keep this cut as an audio file in Music/${RingtoneHelper.EXPORT_FOLDER}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ExportFormat.values().forEach { f ->
                                LengthChip(f.label, selected = exportFormat == f) { if (!busy) exportFormat = f }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { saveCopy() },
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
                            if (exporting) {
                                CircularProgressIndicator(color = onAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Saving…", color = onAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            } else {
                                Text("Save as ${exportFormat.label}", color = onAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
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

/** "1:23.450" */
private fun formatMsPrecise(ms: Long): String {
    val v = ms.coerceAtLeast(0L)
    return "${formatMs(v)}.${(v % 1000L).toString().padStart(3, '0')}"
}

/** "30.000 s" */
private fun formatLength(ms: Long): String = String.format(Locale.US, "%.3f s", ms.coerceAtLeast(0L) / 1000.0)

/** "1:23" in [main] with a smaller ".450" in [fraction], so milliseconds read as detail, not noise. */
private fun preciseTime(ms: Long, main: Color, fraction: Color): AnnotatedString = buildAnnotatedString {
    val v = ms.coerceAtLeast(0L)
    withStyle(SpanStyle(color = main)) { append(formatMs(v)) }
    withStyle(SpanStyle(color = fraction, fontSize = 17.sp)) { append(".${(v % 1000L).toString().padStart(3, '0')}") }
}

@Composable
private fun FineTuneRow(label: String, ms: Long, enabled: Boolean, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text(
                formatMsPrecise(ms), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NUDGE_STEPS_MS.forEach { d ->
                NudgeButton(
                    label = if (d > 0) "+$d" else "\u2212${-d}",
                    description = "${label} ${if (d > 0) "later" else "earlier"} by ${abs(d)} milliseconds",
                    enabled = enabled
                ) { onNudge(d) }
            }
        }
    }
}

/** Small step button: one step on tap, then repeats while held. */
@Composable
private fun NudgeButton(label: String, description: String, enabled: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val isEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TextPrimary.copy(alpha = if (enabled) 0.10f else 0.04f))
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!isEnabled) return@awaitEachGesture
                    step()
                    val repeat: Job = scope.launch {
                        delay(380)
                        while (true) {
                            step()
                            delay(55)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeat.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (enabled) TextPrimary else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1
        )
    }
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

/** Fling / auto-scroll job of the waveform, plus whether the user is dragging the frame (then nothing auto-scrolls). */
private class WaveScroll {
    var job: Job? = null
    var dragging = false
}

/**
 * Zoomed, scrolling waveform with a time ruler (a tick every 100 ms, a label every second) and a selection frame,
 * plus a whole-song overview strip above it for jumping around long songs.
 * - Drag the left or right edge handle to resize (snaps to [SNAP_MS]); the view auto-scrolls near its edges.
 * - Drag inside the frame to move it (keeping its length).
 * - Swipe outside the frame to scroll (with fling); tap outside to jump the frame there.
 */
@Composable
private fun CutterWaveform(
    peaks: FloatArray?,
    totalMs: Long,
    startMs: Long,
    lenMs: Long,
    progress: Float,
    accent: Color,
    onAccent: Color,
    revealMs: Long,
    revealTick: Int,
    onRangeChange: (startMs: Long, lenMs: Long) -> Unit
) {
    val onChange by rememberUpdatedState(onRangeChange)
    // The gestures below must not restart while the window is being resized, so they read the
    // latest values through these instead of using them as pointerInput keys.
    val curStart by rememberUpdatedState(startMs)
    val curLen by rememberUpdatedState(lenMs)
    val placeholder = remember {
        val r = Random(7)
        FloatArray(600) { 0.25f + r.nextFloat() * 0.5f }
    }
    val ink = TextPrimary
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer(cacheSize = 24)
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val ws = remember { WaveScroll() }

    var viewW by remember { mutableIntStateOf(0) }
    var scrollPx by remember { mutableFloatStateOf(0f) }

    val minPxPerMs = with(density) { PX_PER_SEC_DP.dp.toPx() } / 1000f
    val pxPerMs = if (viewW > 0 && totalMs > 0L) max(minPxPerMs, viewW.toFloat() / totalMs) else minPxPerMs
    val contentW = totalMs * pxPerMs
    val maxScroll = max(0f, contentW - viewW)
    val k by rememberUpdatedState(pxPerMs)
    val maxS by rememberUpdatedState(maxScroll)
    val vw by rememberUpdatedState(viewW)
    val margin = with(density) { 28.dp.toPx() }

    fun setScroll(v: Float) {
        scrollPx = v.coerceIn(0f, maxS)
    }

    fun animateScrollTo(target: Float) {
        ws.job?.cancel()
        val from = scrollPx
        val to = target.coerceIn(0f, maxS)
        if (abs(to - from) < 1f) return
        ws.job = scope.launch {
            animate(from, to, animationSpec = tween(300, easing = FastOutSlowInEasing)) { v, _ -> setScroll(v) }
        }
    }

    fun fling(velocity: Float) {
        ws.job?.cancel()
        ws.job = scope.launch {
            AnimationState(initialValue = scrollPx, initialVelocity = velocity).animateDecay(decay) {
                val clamped = value.coerceIn(0f, maxS)
                scrollPx = clamped
                if (clamped != value) cancelAnimation()
            }
        }
    }

    // Keep the frame on screen when it changes from outside the waveform (length chips, first layout).
    LaunchedEffect(startMs, lenMs, viewW, pxPerMs) {
        if (viewW <= 0 || ws.dragging || totalMs <= 0L) return@LaunchedEffect
        val l = startMs * pxPerMs
        val r = (startMs + lenMs) * pxPerMs
        val target = if (r - l + 2 * margin <= viewW) {
            when {
                l - margin < scrollPx -> l - margin
                r + margin > scrollPx + viewW -> r + margin - viewW
                else -> null
            }
        } else if (r < scrollPx || l > scrollPx + viewW) {
            l - margin
        } else {
            null
        }
        if (target != null) animateScrollTo(target)
    }

    // Fine-tune buttons: bring the edge being nudged into view.
    LaunchedEffect(revealTick) {
        if (revealTick == 0 || viewW <= 0) return@LaunchedEffect
        val x = revealMs * pxPerMs
        if (x < scrollPx + margin || x > scrollPx + viewW - margin) animateScrollTo(x - viewW / 2f)
    }

    // Preview playhead: follow it when the frame is wider than the screen.
    val headMs = if (progress >= 0f) startMs + (progress * lenMs).toLong() else -1L
    LaunchedEffect(headMs / 250L, headMs >= 0L) {
        if (headMs < 0L || ws.dragging || viewW <= 0) return@LaunchedEffect
        val x = headMs * pxPerMs
        if (x < scrollPx || x > scrollPx + viewW - margin) animateScrollTo(x - viewW * 0.2f)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Whole-song overview with the visible part outlined. Tap or drag to jump there.
        if (viewW == 0 || contentW > viewW + 1f) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ink.copy(alpha = 0.05f))
                    .pointerInput(totalMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (totalMs <= 0L || size.width <= 0) return@awaitEachGesture
                            ws.job?.cancel()
                            fun go(x: Float) {
                                val ms = (x / size.width).coerceIn(0f, 1f) * totalMs
                                setScroll(ms * k - vw / 2f)
                            }
                            go(down.position.x)
                            drag(down.id) { change ->
                                go(change.position.x)
                                change.consume()
                            }
                        }
                    }
            ) {
                if (totalMs <= 0L) return@Canvas
                val src = peaks ?: placeholder
                val pitch = 3.dp.toPx()
                val barW = 2.dp.toPx()
                val count = (size.width / pitch).toInt().coerceAtLeast(1)
                val selL = size.width * startMs / totalMs
                val selR = size.width * (startMs + lenMs) / totalMs
                val vPad = 5.dp.toPx()
                for (i in 0 until count) {
                    val x = i * pitch
                    val a = (i * src.size / count).coerceIn(0, src.size - 1)
                    val b = ((i + 1) * src.size / count).coerceIn(a + 1, src.size)
                    var p = 0f
                    for (j in a until b) if (src[j] > p) p = src[j]
                    val h = (size.height - 2 * vPad) * (0.15f + 0.85f * p)
                    val cx = x + barW / 2f
                    drawRect(
                        color = if (cx in selL..selR) accent else ink.copy(alpha = 0.25f),
                        topLeft = Offset(x, (size.height - h) / 2f),
                        size = Size(barW, h)
                    )
                }
                if (contentW > 0f && viewW > 0) {
                    val sc = scrollPx.coerceIn(0f, maxScroll)
                    val vx0 = size.width * sc / contentW
                    val vx1 = size.width * (sc + viewW) / contentW
                    val sw = 1.5.dp.toPx()
                    drawRoundRect(
                        color = ink.copy(alpha = 0.10f),
                        topLeft = Offset(vx0, 0f),
                        size = Size(vx1 - vx0, size.height),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                    drawRoundRect(
                        color = ink.copy(alpha = 0.7f),
                        topLeft = Offset(vx0 + sw / 2f, sw / 2f),
                        size = Size(vx1 - vx0 - sw, size.height - sw),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(width = sw)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clipToBounds()
                .onSizeChanged { viewW = it.width }
                .pointerInput(totalMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (totalMs <= 0L || vw <= 0) return@awaitEachGesture
                        ws.job?.cancel()

                        val kk = k
                        val sc0 = scrollPx
                        val s0 = curStart
                        val e0 = s0 + curLen
                        val winL = s0 * kk - sc0
                        val winR = e0 * kk - sc0
                        val x0 = down.position.x

                        // Which part was grabbed. The edge grab zone sticks out a little past each edge so the
                        // handle is easy to catch, and is limited inside so a short window can still be moved.
                        val inner = min(26.dp.toPx(), (winR - winL) / 3f)
                        val outer = 18.dp.toPx()
                        val nearLeft = x0 >= winL - outer && x0 <= winL + inner
                        val nearRight = x0 >= winR - inner && x0 <= winR + outer
                        val mode = when {
                            nearLeft && nearRight -> if (abs(x0 - winL) <= abs(x0 - winR)) GRAB_LEFT else GRAB_RIGHT
                            nearLeft -> GRAB_LEFT
                            nearRight -> GRAB_RIGHT
                            x0 in winL..winR -> GRAB_MOVE
                            else -> GRAB_PAN
                        }
                        // Keep the finger's offset from the edge so the edge doesn't jump to the touch point.
                        val grabOffset = when (mode) {
                            GRAB_LEFT -> x0 - winL
                            GRAB_RIGHT -> x0 - winR
                            else -> 0f
                        }
                        val slop = viewConfiguration.touchSlop
                        val edgeZone = 36.dp.toPx()
                        val autoStep = 10.dp.toPx()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var moved = false

                        fun snap(ms: Float): Long = (ms / SNAP_MS).roundToLong() * SNAP_MS
                        fun msAtView(x: Float): Float = (x + scrollPx) / kk
                        fun autoPan(x: Float) {
                            if (x > vw - edgeZone) setScroll(scrollPx + autoStep)
                            else if (x < edgeZone) setScroll(scrollPx - autoStep)
                        }

                        ws.dragging = mode != GRAB_PAN
                        try {
                            drag(down.id) { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val x = change.position.x
                                val dx = x - x0
                                if (!moved && abs(dx) > slop) moved = true
                                when (mode) {
                                    GRAB_LEFT -> {
                                        autoPan(x)
                                        val maxStart = max(0L, e0 - MIN_CUT_MS)
                                        val ns = snap(msAtView(x - grabOffset)).coerceIn(0L, maxStart)
                                        onChange(ns, e0 - ns)
                                    }
                                    GRAB_RIGHT -> {
                                        autoPan(x)
                                        val minEnd = min(totalMs, s0 + MIN_CUT_MS)
                                        val ne = snap(msAtView(x - grabOffset)).coerceIn(minEnd, totalMs)
                                        onChange(s0, ne - s0)
                                    }
                                    GRAB_MOVE -> if (moved) {
                                        autoPan(x)
                                        val len = curLen
                                        val deltaMs = (dx + (scrollPx - sc0)) / kk
                                        val ns = snap(s0 + deltaMs).coerceIn(0L, max(0L, totalMs - len))
                                        onChange(ns, len)
                                    }
                                    else -> if (moved) setScroll(sc0 - dx)
                                }
                                change.consume()
                            }
                        } finally {
                            ws.dragging = false
                        }

                        if (mode == GRAB_PAN) {
                            if (!moved) {
                                // Tap outside the frame: jump the frame there, centred on the tap.
                                val len = curLen
                                val ns = snap(msAtView(x0) - len / 2f).coerceIn(0L, max(0L, totalMs - len))
                                onChange(ns, len)
                            } else {
                                fling(-tracker.calculateVelocity().x)
                            }
                        }
                    }
                }
        ) {
            if (totalMs <= 0L) return@Canvas
            val kk = pxPerMs
            val sc = scrollPx.coerceIn(0f, maxScroll)
            val rulerH = 26.dp.toPx()
            val waveH = size.height - rulerH
            val vPad = 10.dp.toPx()
            val winL = startMs * kk - sc
            val winR = (startMs + lenMs) * kk - sc
            val radius = CornerRadius(10.dp.toPx())

            // Selection tint behind the bars.
            drawRoundRect(accent.copy(alpha = 0.12f), Offset(winL, 0f), Size(winR - winL, waveH), radius)

            // Faint guide line at every second.
            val firstSec = floor(sc / (kk * 1000f)).toLong().coerceAtLeast(0L)
            val lastSec = min(ceil((sc + size.width) / (kk * 1000f)).toLong(), totalMs / 1000L)
            for (sIdx in firstSec..lastSec) {
                val x = sIdx * 1000f * kk - sc
                drawLine(ink.copy(alpha = 0.07f), Offset(x, 0f), Offset(x, waveH), strokeWidth = 1f)
            }

            // Bars: only the visible slice of the song is drawn.
            val src = peaks ?: placeholder
            val pitch = 4.dp.toPx()
            val barW = 2.5.dp.toPx()
            val msPerBar = pitch / kk
            val headX = if (progress >= 0f) winL + (winR - winL) * progress else -1f
            val dim = if (peaks == null) 0.15f else 0.28f
            val firstBar = floor(sc / pitch).toInt().coerceAtLeast(0)
            val lastBar = ceil((sc + size.width) / pitch).toInt()
            for (i in firstBar..lastBar) {
                val msA = i * msPerBar
                if (msA >= totalMs) break
                val msB = min(totalMs.toFloat(), msA + msPerBar)
                val a = (msA / totalMs * src.size).toInt().coerceIn(0, src.size - 1)
                val b = (msB / totalMs * src.size).toInt().coerceIn(a + 1, src.size)
                var p = 0f
                for (j in a until b) if (src[j] > p) p = src[j]
                val x = i * pitch - sc
                val cx = x + barW / 2f
                val h = (waveH - 2 * vPad) * (0.10f + 0.90f * p)
                val inWindow = cx >= winL && cx <= winR
                val color = when {
                    inWindow && headX >= 0f && cx < headX -> accent
                    inWindow -> ink
                    else -> ink.copy(alpha = dim)
                }
                drawRoundRect(color, Offset(x, (waveH - h) / 2f), Size(barW, h), CornerRadius(barW / 2f))
            }

            // Frame + inner handles with grip lines.
            val stroke = 2.dp.toPx()
            val frameW = max(winR - winL, 8.dp.toPx())
            drawRoundRect(
                color = accent,
                topLeft = Offset(winL + stroke / 2f, stroke / 2f),
                size = Size(frameW - stroke, waveH - stroke),
                cornerRadius = radius,
                style = Stroke(width = stroke)
            )
            val handleW = 12.dp.toPx()
            val gripH = 16.dp.toPx()
            val gripGap = 2.5.dp.toPx()
            val gripW = 1.5.dp.toPx()
            listOf(winL, winL + frameW - handleW).forEach { hx ->
                drawRoundRect(accent, Offset(hx, 0f), Size(handleW, waveH), radius)
                val gx = hx + handleW / 2f
                val gy = (waveH - gripH) / 2f
                drawRoundRect(onAccent, Offset(gx - gripGap - gripW / 2f, gy), Size(gripW, gripH), CornerRadius(gripW))
                drawRoundRect(onAccent, Offset(gx + gripGap - gripW / 2f, gy), Size(gripW, gripH), CornerRadius(gripW))
            }

            // Playhead.
            if (headX >= 0f) {
                drawLine(ink, Offset(headX, 0f), Offset(headX, waveH), strokeWidth = 2.dp.toPx())
                drawCircle(ink, radius = 4.dp.toPx(), center = Offset(headX, 4.dp.toPx()))
            }

            // Ruler: tick every 100 ms (taller at half seconds and seconds), a label every second.
            val baseY = waveH + 2.dp.toPx()
            drawLine(ink.copy(alpha = 0.15f), Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1f)
            val firstTick = (floor(sc / kk / 100f).toLong() * 100L).coerceAtLeast(0L)
            val lastTick = min(ceil((sc + size.width) / kk / 100f).toLong() * 100L, totalMs)
            val labelStyle = TextStyle(fontSize = 10.sp, fontFeatureSettings = "tnum")
            var t = firstTick
            while (t <= lastTick) {
                val x = t * kk - sc
                val isSec = t % 1000L == 0L
                val tickH = when {
                    isSec -> 8.dp.toPx()
                    t % 500L == 0L -> 5.dp.toPx()
                    else -> 3.dp.toPx()
                }
                val inSel = t in startMs..(startMs + lenMs)
                drawLine(
                    color = if (inSel) accent.copy(alpha = if (isSec) 1f else 0.7f) else ink.copy(alpha = if (isSec) 0.5f else 0.25f),
                    start = Offset(x, baseY),
                    end = Offset(x, baseY + tickH),
                    strokeWidth = if (isSec) 1.5.dp.toPx() else 1.dp.toPx()
                )
                if (isSec) {
                    val major = t % 5000L == 0L
                    val layout = textMeasurer.measure(
                        formatMs(t),
                        labelStyle.copy(
                            color = if (major) TextSecondary else TextMuted,
                            fontWeight = if (major) FontWeight.Medium else FontWeight.Normal
                        )
                    )
                    val lx = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                    drawText(layout, topLeft = Offset(lx, baseY + 9.dp.toPx()))
                }
                t += 100L
            }
        }
    }
}

private const val GRAB_MOVE = 0
private const val GRAB_LEFT = 1
private const val GRAB_RIGHT = 2
private const val GRAB_PAN = 3

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
