package com.beatraxus.app.ui.screens

import androidx.activity.compose.BackHandler
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beatraxus.app.engine.WaveformExtractor
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongQualityEntity
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Music Detail Inspector — an "audiophile lab" reading of a single track. Deliberately
 * distinct from DspScreen's cyan premium-glass look: near-black base with a colorful,
 * blurred ambient backdrop pulled from the album art, and each instrument panel below
 * gets its own accent color (a small palette, not a single flat theme color) so the
 * screen reads as a set of distinct meters rather than one monotone surface.
 */
private object InspectorPalette {
    val Bg = Color(0xFF07060B)
    val Quality = Color(0xFFFFC94A)      // amber — overall score
    val Waveform = Color(0xFF29E1D6)     // cyan/teal
    val Spectrogram = Color(0xFFFF5FA8)  // magenta (label only; heatmap uses its own thermal scale)
    val LiveMeters = Color(0xFF43E97B)   // VU green
    val Metadata = Color(0xFF5B8CFF)     // blue
    val ReplayGain = Color(0xFFFFA83D)   // orange
    val Codec = Color(0xFF2FE6C7)        // teal
    val Artwork = Color(0xFFFF6FCB)      // pink
    val Lyrics = Color(0xFF9C7CFF)       // indigo/violet
    val Tags = Color(0xFF8FA3C7)         // slate blue-gray
}

@Composable
fun MusicDetailInspectorScreen(
    songId: String,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val song by remember(songId) { viewModel.songByIdFlow(songId) }.collectAsState(initial = null)
    val quality by remember(songId) { viewModel.songQualityFlow(songId) }.collectAsState(initial = null)
    val uiState by viewModel.uiState.collectAsState()
    val progressMs by viewModel.progressMs.collectAsState()

    // System/gesture back must behave identically to the on-screen back arrow (which
    // restores the Now Playing info dialog / options-sheet state via onBack below) —
    // previously the system back button just popped the nav stack directly and skipped
    // that restoration.
    BackHandler(onBack = onBack)

    // If this song hasn't been scored yet, kick off analysis immediately rather than
    // waiting for the next periodic scan (which may never queue this song again — see
    // requestQualityAnalysis in PlayerViewModel).
    LaunchedEffect(songId, quality) {
        if (quality == null) {
            song?.let { viewModel.requestQualityAnalysis(it) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(InspectorPalette.Bg)) {
        val currentSong = song
        if (currentSong == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = InspectorPalette.Quality)
            }
        } else {
            AmbientBackdrop(currentSong)

            val isCurrentlyPlaying = uiState.currentSong?.id == currentSong.id && uiState.isPlaying
            val isCurrentSong = uiState.currentSong?.id == currentSong.id

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                InspectorHeader(currentSong, quality, onBack)

                Spacer(Modifier.height(18.dp))
                QualityScoreCard(quality)

                Spacer(Modifier.height(14.dp))
                WaveformCard(
                    song = currentSong,
                    isCurrentSong = isCurrentSong,
                    isPlaying = isCurrentlyPlaying,
                    progressMs = progressMs
                )

                Spacer(Modifier.height(14.dp))
                SpectrogramCard(currentSong)

                Spacer(Modifier.height(14.dp))
                LiveMetersCard(viewModel, isActive = isCurrentlyPlaying, quality = quality)

                Spacer(Modifier.height(14.dp))
                MetadataCard(currentSong)

                Spacer(Modifier.height(14.dp))
                ReplayGainCard(currentSong)

                Spacer(Modifier.height(14.dp))
                CodecCard(currentSong, quality)

                Spacer(Modifier.height(14.dp))
                ArtworkCard(currentSong)

                if (!currentSong.lyrics.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    LyricsCard(currentSong)
                }

                Spacer(Modifier.height(14.dp))
                TagsCard(currentSong)

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ambient backdrop — heavily blurred, dimmed album art behind everything, the way a
// premium "now playing" surface feels alive instead of flat black. Mirrors the blur
// approach NowPlayingScreen already uses (Modifier.blur), kept simple here since this
// is a background decoration, not a focal element.
// ---------------------------------------------------------------------------
@Composable
private fun BoxScope.AmbientBackdrop(song: Song) {
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
            .background(Color.Black.copy(alpha = 0.35f))
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .align(Alignment.TopCenter)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.25f),
                        InspectorPalette.Bg.copy(alpha = 0.55f),
                        InspectorPalette.Bg
                    )
                )
            )
    )
}

// ---------------------------------------------------------------------------
// Shared "instrument card" shell — icon + caps-lock label in the card's own accent
// color, soft accent-tinted glow border, subtle top highlight. Each call site passes
// its own color from InspectorPalette so the screen reads as colorful panel-per-metric
// rather than one flat theme.
// ---------------------------------------------------------------------------
@Composable
private fun InstrumentCard(
    label: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.03f)
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun InspectorHeader(song: Song, quality: SongQualityEntity?, onBack: () -> Unit) {
    val tint = quality?.let { tierColor(it.qualityTier) } ?: InspectorPalette.Quality
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .background(Color.White.copy(0.06f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${song.artist} • ${song.album}",
                color = Color.White.copy(0.65f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Overall quality score — gradient radial dial + tier badge
// ---------------------------------------------------------------------------
@Composable
private fun QualityScoreCard(quality: SongQualityEntity?) {
    InstrumentCard(label = "OVERALL QUALITY", accent = InspectorPalette.Quality, icon = Icons.Rounded.WorkspacePremium) {
        if (quality == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(color = InspectorPalette.Quality, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Analyzing on next scan…", color = Color.White.copy(0.5f), fontSize = 13.sp)
            }
            return@InstrumentCard
        }

        val tierColor = tierColor(quality.qualityTier)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 11.dp.toPx()
                    drawArc(
                        color = Color.White.copy(0.08f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(tierColor.copy(alpha = 0.3f), tierColor, tierColor.copy(alpha = 0.3f))
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f * (quality.qualityScore / 100f),
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${quality.qualityScore}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("/ 100", color = Color.White.copy(0.4f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(listOf(tierColor.copy(alpha = 0.25f), tierColor.copy(alpha = 0.1f)))
                        )
                        .border(1.dp, tierColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(quality.qualityTier, color = tierColor, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                StatChip("LUFS", "%.1f".format(quality.lufs), InspectorPalette.Metadata)
                Spacer(Modifier.height(6.dp))
                StatChip("DR", "%.1f dB".format(quality.dynamicRange), InspectorPalette.Waveform)
                Spacer(Modifier.height(6.dp))
                StatChip("True Peak", "%.1f dBFS".format(quality.truePeakDb), InspectorPalette.ReplayGain)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White.copy(0.45f), fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Text(value, color = Color.White.copy(0.85f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

private fun tierColor(tier: String): Color = when (tier) {
    "Excellent" -> Color(0xFF43E97B)
    "Good" -> Color(0xFF5B8CFF)
    "Fair" -> Color(0xFFFFC94A)
    else -> Color(0xFFFF5A6E)
}

// ---------------------------------------------------------------------------
// 3. Waveform — filled gradient envelope, decoded + cached by WaveformExtractor
// ---------------------------------------------------------------------------
@Composable
private fun WaveformCard(song: Song, isCurrentSong: Boolean, isPlaying: Boolean, progressMs: Long) {
    val context = LocalContext.current
    var data by remember(song.id) { mutableStateOf<WaveformExtractor.WaveformData?>(null) }
    var failed by remember(song.id) { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        data = null
        failed = false
        val result = WaveformExtractor.getOrExtract(context, song.id, song.uri)
        if (result == null) failed = true else data = result
    }

    // Live progress fraction along the track — only meaningful while this is the song
    // actually loaded in the player. Turns the (still statically-decoded) waveform shape
    // into a live playback readout: a moving playhead plus a played/unplayed split, the
    // same way the shape stays fixed but the progress on a DAW's overview track is live.
    val progressFraction = if (isCurrentSong && song.durationMs > 0) {
        (progressMs.toFloat() / song.durationMs.toFloat()).coerceIn(0f, 1f)
    } else null

    InstrumentCard(
        label = "WAVEFORM",
        accent = InspectorPalette.Waveform,
        icon = Icons.Rounded.GraphicEq,
        trailing = {
            if (isCurrentSong && isPlaying) {
                LiveBadge(InspectorPalette.Waveform)
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(Color.Black.copy(0.35f), RoundedCornerShape(10.dp))
        ) {
            val d = data
            when {
                d != null -> Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    drawWaveform(d.minPeaks, d.maxPeaks, InspectorPalette.Waveform, progressFraction)
                }
                failed -> Text(
                    "Waveform unavailable for this file",
                    color = Color.White.copy(0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> CircularProgressIndicator(
                    color = InspectorPalette.Waveform,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/** Small pulsing-dot "LIVE" chip used on cards that reflect real-time playback state. */
@Composable
private fun LiveBadge(color: Color) {
    val infinite = rememberInfiniteTransition(label = "live-pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "live-pulse-alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        Spacer(Modifier.width(5.dp))
        Text("LIVE", color = color, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveform(
    min: FloatArray,
    max: FloatArray,
    color: Color,
    progressFraction: Float?
) {
    if (min.isEmpty()) return
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val step = w / min.size
    val gradient = Brush.verticalGradient(
        colors = listOf(color, color.copy(alpha = 0.35f), color)
    )
    val playedX = progressFraction?.let { it * w }
    for (i in min.indices) {
        val x = i * step
        val yTop = midY - (max[i].coerceIn(-1f, 1f) * midY)
        val yBottom = midY - (min[i].coerceIn(-1f, 1f) * midY)
        // Bars ahead of the live playhead are dimmed so the played portion reads clearly,
        // the same convention as a streaming-app seek waveform.
        val barBrush = if (playedX != null && x > playedX) {
            Brush.verticalGradient(listOf(color.copy(0.22f), color.copy(0.12f), color.copy(0.22f)))
        } else gradient
        drawLine(
            brush = barBrush,
            start = Offset(x, yTop),
            end = Offset(x, yBottom),
            strokeWidth = step.coerceAtLeast(1.2f),
            cap = StrokeCap.Round
        )
    }
    // Center reference line — classic DAW waveform look.
    drawLine(color.copy(alpha = 0.25f), Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)

    if (playedX != null) {
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(playedX, 0f),
            end = Offset(playedX, h),
            strokeWidth = 2f
        )
    }
}

// ---------------------------------------------------------------------------
// 4. Spectrogram — thermal (blue → green → yellow → red) time/frequency heatmap
// ---------------------------------------------------------------------------
@Composable
private fun SpectrogramCard(song: Song) {
    val context = LocalContext.current
    var data by remember(song.id) { mutableStateOf<WaveformExtractor.WaveformData?>(null) }

    LaunchedEffect(song.id) {
        data = WaveformExtractor.getOrExtract(context, song.id, song.uri)
    }

    val lossless = remember(song.format) { isLosslessFormat(song.format) }

    InstrumentCard(
        label = "SPECTROGRAM",
        accent = InspectorPalette.Spectrogram,
        icon = Icons.Rounded.Equalizer,
        trailing = { LosslessBadge(lossless) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black.copy(0.4f), RoundedCornerShape(10.dp))
        ) {
            val frames = data?.spectrogramFrames
            if (frames != null && frames.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawSpectrogram(frames)
                }
            } else {
                CircularProgressIndicator(
                    color = InspectorPalette.Spectrogram,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(thermalColor(0f), thermalColor(0.33f), thermalColor(0.66f), thermalColor(1f))
                        )
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text("quiet → loud  •  low → high freq bottom → top", color = Color.White.copy(0.4f), fontSize = 10.sp)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpectrogram(frames: Array<FloatArray>) {
    val cols = frames.size
    val rows = frames.firstOrNull()?.size ?: return
    val cellW = size.width / cols
    val cellH = size.height / rows
    for (c in 0 until cols) {
        val frame = frames[c]
        for (r in 0 until rows) {
            drawRect(
                color = thermalColor(frame[r].coerceIn(0f, 1f)),
                topLeft = Offset(c * cellW, size.height - (r + 1) * cellH),
                size = Size(cellW + 0.5f, cellH + 0.5f)
            )
        }
    }
}

/** Format check reused from the same convention as SongListItem/NowPlayingScreen's
 *  lossless badge, so the Inspector agrees with the rest of the app about what counts
 *  as lossless. */
private fun isLosslessFormat(format: String): Boolean {
    val f = format.lowercase()
    return f.contains("flac") || f.contains("alac") || f.contains("wav") ||
        f.contains("dsd") || f.contains("aiff") || f.contains("dts") || f.contains("ac3") ||
        f.contains("ape") || f.contains("wv")
}

@Composable
private fun LosslessBadge(isLossless: Boolean) {
    val color = if (isLossless) Color(0xFF43E97B) else Color.White.copy(alpha = 0.35f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = if (isLossless) 0.15f else 0.06f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text("LOSSLESS", color = color, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
        if (isLossless) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Check, contentDescription = "Lossless", tint = color, modifier = Modifier.size(11.dp))
        }
    }
}

/** Classic thermal/spectrogram palette: near-black → blue → teal → green → yellow → red. */
private fun thermalColor(mag: Float): Color {
    val stops = listOf(
        0.00f to Color(0xFF07060B),
        0.20f to Color(0xFF2A2A8C),
        0.40f to Color(0xFF1E9BD7),
        0.60f to Color(0xFF29E17A),
        0.80f to Color(0xFFF6E24C),
        1.00f to Color(0xFFFF3B3B)
    )
    for (i in 0 until stops.size - 1) {
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        if (mag in p0..p1) {
            val t = if (p1 > p0) (mag - p0) / (p1 - p0) else 0f
            return lerpColor(c0, c1, t)
        }
    }
    return stops.last().second
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val ct = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * ct,
        green = a.green + (b.green - a.green) * ct,
        blue = a.blue + (b.blue - a.blue) * ct,
        alpha = a.alpha + (b.alpha - a.alpha) * ct
    )
}

// ---------------------------------------------------------------------------
// 5. Live meters during playback — VU-style green/yellow/red FFT bars, phase
// correlation, level meters.
//
// Reads AudioTrackOutput.captureLiveWindow() (via PlayerViewModel) instead of
// android.media.audiofx.Visualizer. Visualizer only taps the regular mixer session, so it
// went dark during MMAP-exclusive (bit-perfect) output — which is exactly why this card
// used to show "Live meters aren't available for the current output mode." Reading
// straight from the PCM pipeline instead means these meters now work in every output
// mode. Capture is mixed-down for the spectrum/level math, so "phase" below is a
// correlation proxy, not true L/R — called out in the UI rather than fabricating fake
// per-channel data. Peak/RMS here are a fast windowed approximation ("approx."); the
// exact ITU-R BS.1770 LUFS is the static "Overall" figure in the Quality card above.
// ---------------------------------------------------------------------------
private const val LIVE_FFT_SIZE = 512

@Composable
private fun LiveMetersCard(viewModel: PlayerViewModel, isActive: Boolean, quality: SongQualityEntity?) {
    InstrumentCard(
        label = "LIVE METERS",
        accent = InspectorPalette.LiveMeters,
        icon = Icons.Rounded.Speed,
        trailing = { if (isActive) LiveBadge(InspectorPalette.LiveMeters) }
    ) {
        if (!isActive) {
            Text(
                "Play this track to see live FFT, phase, and level meters.",
                color = Color.White.copy(0.4f),
                fontSize = 12.sp
            )
            return@InstrumentCard
        }

        var fftBars by remember { mutableStateOf(FloatArray(32)) }
        var peakBars by remember { mutableStateOf(FloatArray(32)) }
        var correlation by remember { mutableStateOf(0f) }
        var rmsDb by remember { mutableStateOf(-60f) }
        var peakDb by remember { mutableStateOf(-60f) }
        var noSignalYet by remember { mutableStateOf(true) }

        LaunchedEffect(viewModel) {
            val ring = FloatArray(LIVE_FFT_SIZE)
            var ringPos = 0
            while (true) {
                val capture = viewModel.captureLiveWindow()
                if (capture != null && capture.samples.isNotEmpty()) {
                    noSignalYet = false
                    val ch = capture.channels.coerceAtLeast(1)
                    val frames = capture.samples.size / ch
                    if (frames > 0) {
                        var sumSq = 0.0
                        var peak = 0f
                        var corrSum = 0.0
                        for (f in 0 until frames) {
                            val l = capture.samples[f * ch]
                            val r = if (ch > 1) capture.samples[f * ch + 1] else l
                            val mono = (l + r) * 0.5f
                            ring[ringPos] = mono
                            ringPos = (ringPos + 1) % LIVE_FFT_SIZE
                            if (abs(l) > peak) peak = abs(l)
                            if (abs(r) > peak) peak = abs(r)
                            sumSq += (mono * mono).toDouble()
                            corrSum += (l * r).toDouble()
                        }
                        val rms = sqrt(sumSq / frames)
                        rmsDb = (20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-6))).toFloat()
                        peakDb = (20.0 * kotlin.math.log10(peak.toDouble().coerceAtLeast(1e-6))).toFloat()
                        correlation = (corrSum / frames).toFloat().coerceIn(-1f, 1f)

                        val windowed = FloatArray(LIVE_FFT_SIZE) { idx ->
                            val sample = ring[(ringPos + idx) % LIVE_FFT_SIZE]
                            val hann = 0.5f - 0.5f * cos(2.0 * PI * idx / (LIVE_FFT_SIZE - 1)).toFloat()
                            sample * hann
                        }
                        val mags = fftMagnitude(windowed)
                        val bars = FloatArray(32)
                        val binsPerBar = (mags.size / bars.size).coerceAtLeast(1)
                        for (b in bars.indices) {
                            var s = 0f
                            for (i in 0 until binsPerBar) {
                                val idx = b * binsPerBar + i
                                if (idx < mags.size) s += mags[idx]
                            }
                            bars[b] = (s / binsPerBar / (LIVE_FFT_SIZE / 4f)).coerceIn(0f, 1f)
                        }
                        fftBars = bars
                        peakBars = FloatArray(32) { i -> maxOf(bars[i], peakBars.getOrElse(i) { 0f } * 0.92f) }
                    }
                }
                delay(45)
            }
        }

        if (noSignalYet) {
            Text(
                "Waiting for playback signal…",
                color = Color.White.copy(0.4f),
                fontSize = 12.sp
            )
        }

        Text("FFT SPECTRUM", color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            fftBars.forEachIndexed { i, v ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(v.coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(1.dp))
                            .background(Brush.verticalGradient(vuBarColors))
                    )
                    // Peak-hold cap, classic VU meter behavior.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .offset(y = -(peakBars.getOrElse(i) { v }.coerceIn(0.03f, 1f) * 56).dp)
                            .background(Color.White.copy(alpha = 0.7f))
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LiveMeterStat("PEAK (approx.)", "%.1f dB".format(peakDb), InspectorPalette.LiveMeters)
            LiveMeterStat("RMS (approx.)", "%.1f dB".format(rmsDb), InspectorPalette.Waveform)
            LiveMeterStat("PHASE", "%.2f".format(correlation), InspectorPalette.Spectrogram)
            LiveMeterStat("LUFS (Overall)", quality?.let { "%.1f".format(it.lufs) } ?: "—", InspectorPalette.Metadata)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Phase is a correlation proxy from a mixed-down capture — this device doesn't expose true per-channel L/R here.",
            color = Color.White.copy(0.35f),
            fontSize = 10.sp
        )
    }
}

/** Minimal iterative radix-2 Cooley-Tukey FFT. `real` must have power-of-two length.
 *  Returns magnitude for the positive-frequency bins (0 until n/2). Not a general-purpose
 *  DSP utility — sized and tuned specifically for the Live Meters spectrum bars. */
private fun fftMagnitude(real: FloatArray): FloatArray {
    val n = real.size
    val re = real.copyOf()
    val im = FloatArray(n)

    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }

    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wr = cos(ang).toFloat()
        val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var curWr = 1f
            var curWi = 0f
            for (k in 0 until len / 2) {
                val uRe = re[i + k]
                val uIm = im[i + k]
                val vRe = re[i + k + len / 2] * curWr - im[i + k + len / 2] * curWi
                val vIm = re[i + k + len / 2] * curWi + im[i + k + len / 2] * curWr
                re[i + k] = uRe + vRe
                im[i + k] = uIm + vIm
                re[i + k + len / 2] = uRe - vRe
                im[i + k + len / 2] = uIm - vIm
                val nextWr = curWr * wr - curWi * wi
                val nextWi = curWr * wi + curWi * wr
                curWr = nextWr
                curWi = nextWi
            }
            i += len
        }
        len = len shl 1
    }

    val half = n / 2
    val mags = FloatArray(half)
    for (i in 0 until half) {
        mags[i] = sqrt(re[i] * re[i] + im[i] * im[i])
    }
    return mags
}

/** Classic VU meter gradient: green low, yellow mid, red near clipping. */
private val vuBarColors = listOf(
    Color(0xFFFF3B3B),
    Color(0xFFF6E24C),
    Color(0xFF43E97B)
)

@Composable
private fun LiveMeterStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = color.copy(0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// 6. Metadata card
// ---------------------------------------------------------------------------
@Composable
private fun MetadataCard(song: Song) {
    InstrumentCard(label = "METADATA", accent = InspectorPalette.Metadata, icon = Icons.Rounded.Info) {
        InspectorRow("Title", song.title)
        InspectorRow("Artist", song.artist)
        InspectorRow("Album", song.album)
        song.albumArtist?.let { InspectorRow("Album Artist", it) }
        song.composer?.let { InspectorRow("Composer", it) }
        InspectorRow("Genre", song.genre)
        if (song.year > 0) InspectorRow("Year", song.year.toString())
        if (song.trackNumber != null || song.discNumber != null) {
            InspectorRow("Track / Disc", "${song.trackNumber ?: "-"} / ${song.discNumber ?: "-"}")
        }
        InspectorRow("Duration", formatDuration(song.durationMs))
        InspectorRow("File Size", formatFileSize(song.fileSizeBytes))
        InspectorRow("Location", song.folder)
    }
}

// ---------------------------------------------------------------------------
// 7. ReplayGain card
// ---------------------------------------------------------------------------
@Composable
private fun ReplayGainCard(song: Song) {
    val hasAny = song.replayGainTrackDb != null || song.replayGainAlbumDb != null ||
        song.replayGainTrackPeak != null || song.replayGainAlbumPeak != null
    InstrumentCard(label = "REPLAYGAIN", accent = InspectorPalette.ReplayGain, icon = Icons.Rounded.Tune) {
        if (!hasAny) {
            Text("No ReplayGain tags found", color = Color.White.copy(0.4f), fontSize = 12.sp)
            return@InstrumentCard
        }
        song.replayGainTrackDb?.let { InspectorRow("Track Gain", "%.2f dB".format(it)) }
        song.replayGainAlbumDb?.let { InspectorRow("Album Gain", "%.2f dB".format(it)) }
        song.replayGainTrackPeak?.let { InspectorRow("Track Peak", "%.4f".format(it)) }
        song.replayGainAlbumPeak?.let { InspectorRow("Album Peak", "%.4f".format(it)) }
    }
}

// ---------------------------------------------------------------------------
// 8. Codec information card
// ---------------------------------------------------------------------------
@Composable
private fun CodecCard(song: Song, quality: SongQualityEntity?) {
    InstrumentCard(label = "CODEC INFORMATION", accent = InspectorPalette.Codec, icon = Icons.Rounded.Memory) {
        InspectorRow("Format", song.format.uppercase())
        InspectorRow("Bitrate", if (song.bitrate > 0) "${song.bitrate} kbps" else "—")
        InspectorRow("Sample Rate", "${song.sampleRateHz / 1000.0} kHz")
        InspectorRow("Bit Depth", "${song.bitDepth}-bit")
        quality?.let {
            if (it.freqRangeLowHz > 0 || it.freqRangeHighHz > 0) {
                InspectorRow("Frequency Range", "${it.freqRangeLowHz.toInt()} Hz – ${(it.freqRangeHighHz / 1000f)} kHz")
            }
            InspectorRow("Clipped Samples", "%.3f%%".format(it.clippedSamplePct))
        }
    }
}

// ---------------------------------------------------------------------------
// 9. Embedded artwork card
// ---------------------------------------------------------------------------
@Composable
private fun ArtworkCard(song: Song) {
    InstrumentCard(label = "EMBEDDED ARTWORK", accent = InspectorPalette.Artwork, icon = Icons.Rounded.Image) {
        if (song.albumArtUri == null) {
            Text("No embedded artwork found", color = Color.White.copy(0.4f), fontSize = 12.sp)
            return@InstrumentCard
        }
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = "Full artwork",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, InspectorPalette.Artwork.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
        )
    }
}

// ---------------------------------------------------------------------------
// 10. Lyrics card
// ---------------------------------------------------------------------------
@Composable
private fun LyricsCard(song: Song) {
    InstrumentCard(label = "LYRICS", accent = InspectorPalette.Lyrics, icon = Icons.Rounded.Subtitles) {
        Text(
            song.lyrics.orEmpty(),
            color = Color.White.copy(0.75f),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

// ---------------------------------------------------------------------------
// 11. Tags card — remaining fields not already surfaced above
// ---------------------------------------------------------------------------
@Composable
private fun TagsCard(song: Song) {
    InstrumentCard(label = "TAGS", accent = InspectorPalette.Tags, icon = Icons.Rounded.Label) {
        InspectorRow("Source", song.source.name)
        if (song.dateAdded > 0) {
            InspectorRow("Date Added", java.text.SimpleDateFormat("dd MMM yyyy").format(java.util.Date(song.dateAdded)))
        }
        InspectorRow("Enriched", if (song.isEnriched) "Yes" else "No")
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(0.45f), fontSize = 12.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private fun formatDuration(ms: Long): String {
    val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(m, s)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb > 1000) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
