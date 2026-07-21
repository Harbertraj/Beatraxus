package com.beatraxus.app.ui.screens

import android.media.audiofx.Visualizer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.engine.WaveformExtractor
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongQualityEntity
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlin.math.abs
import kotlin.math.sqrt

// Deliberately distinct from DspScreen's cyan premium-glass look (PremiumAccent =
// 0xFF00F2FF) so the Inspector reads as its own "lab instrument" surface: deep
// near-black background, violet/magenta accent, hairline grid behind the meters.
private val InspectorBg = Color(0xFF06050A)
private val InspectorAccent = Color(0xFFB388FF)

@Composable
fun MusicDetailInspectorScreen(
    songId: String,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val song by remember(songId) { viewModel.songByIdFlow(songId) }.collectAsState(initial = null)
    val quality by remember(songId) { viewModel.songQualityFlow(songId) }.collectAsState(initial = null)
    val uiState by viewModel.uiState.collectAsState()

    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InspectorBg)
    ) {
        val currentSong = song
        if (currentSong == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = InspectorAccent)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                InspectorHeader(currentSong, onBack)

                Spacer(Modifier.height(16.dp))
                QualityScoreCard(quality)

                Spacer(Modifier.height(12.dp))
                WaveformCard(currentSong)

                Spacer(Modifier.height(12.dp))
                SpectrogramCard(currentSong)

                val isCurrentlyPlaying = uiState.currentSong?.id == currentSong.id && uiState.isPlaying
                Spacer(Modifier.height(12.dp))
                LiveMetersCard(viewModel, isActive = isCurrentlyPlaying, quality = quality)

                Spacer(Modifier.height(12.dp))
                MetadataCard(currentSong)

                Spacer(Modifier.height(12.dp))
                ReplayGainCard(currentSong)

                Spacer(Modifier.height(12.dp))
                CodecCard(currentSong, quality)

                Spacer(Modifier.height(12.dp))
                ArtworkCard(currentSong)

                if (!currentSong.lyrics.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    LyricsCard(currentSong)
                }

                Spacer(Modifier.height(12.dp))
                TagsCard(currentSong)

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared "instrument card" shell — caps-lock label header in the violet accent,
// a distinct card shape so this reads differently from the DSP screen's cards.
// ---------------------------------------------------------------------------
@Composable
private fun InstrumentCard(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(
                width = 1.dp,
                color = InspectorAccent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            label,
            color = InspectorAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun InspectorHeader(song: Song, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.06f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${song.artist} • ${song.album}",
                color = Color.White.copy(0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Overall quality score — radial readout + tier label
// ---------------------------------------------------------------------------
@Composable
private fun QualityScoreCard(quality: SongQualityEntity?) {
    InstrumentCard(label = "OVERALL QUALITY") {
        if (quality == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(color = InspectorAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Analyzing on next scan…", color = Color.White.copy(0.5f), fontSize = 13.sp)
            }
            return@InstrumentCard
        }

        val tierColor = tierColor(quality.qualityTier)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    drawArc(
                        color = Color.White.copy(0.08f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = tierColor,
                        startAngle = -90f,
                        sweepAngle = 360f * (quality.qualityScore / 100f),
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${quality.qualityScore}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("/ 100", color = Color.White.copy(0.4f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tierColor.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(quality.qualityTier, color = tierColor, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("LUFS ${"%.1f".format(quality.lufs)}", color = Color.White.copy(0.6f), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("DR ${"%.1f".format(quality.dynamicRange)} dB", color = Color.White.copy(0.6f), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("True Peak ${"%.1f".format(quality.truePeakDb)} dBFS", color = Color.White.copy(0.6f), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

private fun tierColor(tier: String): Color = when (tier) {
    "Excellent" -> Color(0xFF4CD964)
    "Good" -> Color(0xFF64B5F6)
    "Fair" -> Color(0xFFFFB74D)
    else -> Color(0xFFFF5252)
}

// ---------------------------------------------------------------------------
// 3. Waveform — decoded min/max envelope, cached to disk by WaveformExtractor
// ---------------------------------------------------------------------------
@Composable
private fun WaveformCard(song: Song) {
    val context = LocalContext.current
    var data by remember(song.id) { mutableStateOf<WaveformExtractor.WaveformData?>(null) }
    var failed by remember(song.id) { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        data = null
        failed = false
        val result = WaveformExtractor.getOrExtract(context, song.id, song.uri)
        if (result == null) failed = true else data = result
    }

    InstrumentCard(label = "WAVEFORM") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(Color.Black.copy(0.3f), RoundedCornerShape(8.dp))
        ) {
            val d = data
            when {
                d != null -> Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    drawWaveform(d.minPeaks, d.maxPeaks, InspectorAccent)
                }
                failed -> Text(
                    "Waveform unavailable for this file",
                    color = Color.White.copy(0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> CircularProgressIndicator(
                    color = InspectorAccent,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveform(min: FloatArray, max: FloatArray, color: Color) {
    if (min.isEmpty()) return
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val step = w / min.size
    for (i in min.indices) {
        val x = i * step
        val yTop = midY - (max[i].coerceIn(-1f, 1f) * midY)
        val yBottom = midY - (min[i].coerceIn(-1f, 1f) * midY)
        drawLine(
            color = color.copy(alpha = 0.85f),
            start = Offset(x, yTop),
            end = Offset(x, yBottom),
            strokeWidth = step.coerceAtLeast(1f)
        )
    }
}

// ---------------------------------------------------------------------------
// 4. Spectrogram — time-vs-frequency heatmap from per-frame FFT buckets
// ---------------------------------------------------------------------------
@Composable
private fun SpectrogramCard(song: Song) {
    val context = LocalContext.current
    var data by remember(song.id) { mutableStateOf<WaveformExtractor.WaveformData?>(null) }

    LaunchedEffect(song.id) {
        data = WaveformExtractor.getOrExtract(context, song.id, song.uri)
    }

    InstrumentCard(label = "SPECTROGRAM") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Color.Black.copy(0.35f), RoundedCornerShape(8.dp))
        ) {
            val frames = data?.spectrogramFrames
            if (frames != null && frames.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawSpectrogram(frames)
                }
            } else {
                CircularProgressIndicator(
                    color = InspectorAccent,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "dark → violet → white = quiet → loud, low → high frequency bottom → top",
            color = Color.White.copy(0.35f),
            fontSize = 10.sp
        )
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
            val mag = frame[r].coerceIn(0f, 1f)
            drawRect(
                color = spectrogramColor(mag),
                topLeft = Offset(c * cellW, size.height - (r + 1) * cellH),
                size = Size(cellW + 0.5f, cellH + 0.5f)
            )
        }
    }
}

private fun spectrogramColor(mag: Float): Color = when {
    mag < 0.33f -> lerpColor(Color(0xFF06050A), InspectorAccent.copy(alpha = 0.5f), mag / 0.33f)
    mag < 0.7f -> lerpColor(InspectorAccent.copy(alpha = 0.5f), InspectorAccent, (mag - 0.33f) / 0.37f)
    else -> lerpColor(InspectorAccent, Color.White, (mag - 0.7f) / 0.3f)
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
// 5. Live meters during playback — FFT bars, phase correlation, level meters
//
// Uses android.media.audiofx.Visualizer attached to the current playback's AudioTrack
// session id (there was no existing Visualizer/audio-session tap in this codebase prior
// to this feature — AudioTrackOutput.getAudioSessionId() was added alongside this
// screen). FFT/waveform capture from Visualizer is mixed-down (post-mix), so the phase
// readout below is a correlation-only approximation, not true per-channel L/R — this is
// called out in the UI rather than fabricating fake per-channel data. The peak/RMS
// meters here are a fast windowed approximation (labeled "approx."); the exact
// ITU-R BS.1770 LUFS value is the static "Overall" number from the native analysis
// shown in the Quality card above.
// ---------------------------------------------------------------------------
@Composable
private fun LiveMetersCard(viewModel: PlayerViewModel, isActive: Boolean, quality: SongQualityEntity?) {
    InstrumentCard(label = "LIVE METERS") {
        if (!isActive) {
            Text(
                "Play this track to see live FFT, phase, and level meters.",
                color = Color.White.copy(0.4f),
                fontSize = 12.sp
            )
            return@InstrumentCard
        }

        var fftBars by remember { mutableStateOf(FloatArray(32)) }
        var correlation by remember { mutableStateOf(0f) }
        var rmsDb by remember { mutableStateOf(-60f) }
        var peakDb by remember { mutableStateOf(-60f) }
        var visualizerError by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            var visualizer: Visualizer? = null
            try {
                val sessionId = viewModel.getCurrentAudioSessionId()
                if (sessionId != 0) {
                    visualizer = Visualizer(sessionId).apply {
                        captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                        setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                                if (waveform == null) return
                                var sumSq = 0.0
                                var peak = 0
                                var corrSum = 0.0
                                var i = 0
                                while (i < waveform.size) {
                                    val a = (waveform[i].toInt() and 0xFF) - 128
                                    val b = if (i + 1 < waveform.size) (waveform[i + 1].toInt() and 0xFF) - 128 else a
                                    sumSq += (a * a).toDouble()
                                    peak = kotlin.math.max(peak, abs(a))
                                    corrSum += (a * b).toDouble()
                                    i += 2
                                }
                                val n = (waveform.size / 2).coerceAtLeast(1)
                                val rms = sqrt(sumSq / n) / 128.0
                                rmsDb = (20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-6))).toFloat()
                                peakDb = (20.0 * kotlin.math.log10((peak / 128.0).coerceAtLeast(1e-6))).toFloat()
                                correlation = (corrSum / (n * 128.0 * 128.0)).toFloat().coerceIn(-1f, 1f)
                            }

                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                                if (fft == null) return
                                val bars = FloatArray(32)
                                val binsPerBar = ((fft.size / 2) / bars.size).coerceAtLeast(1)
                                for (b in bars.indices) {
                                    var sum = 0f
                                    for (i in 0 until binsPerBar) {
                                        val idx = (b * binsPerBar + i) * 2
                                        if (idx + 1 < fft.size) {
                                            val re = fft[idx].toFloat()
                                            val im = fft[idx + 1].toFloat()
                                            sum += sqrt(re * re + im * im)
                                        }
                                    }
                                    bars[b] = (sum / binsPerBar / 128f).coerceIn(0f, 1f)
                                }
                                fftBars = bars
                            }
                        }, Visualizer.getMaxCaptureRate() / 2, true, true)
                        enabled = true
                    }
                } else {
                    visualizerError = true
                }
            } catch (t: Throwable) {
                visualizerError = true
            }

            onDispose {
                // Visualizer is a real system resource — must be released or it leaks.
                try {
                    visualizer?.enabled = false
                    visualizer?.release()
                } catch (_: Exception) {}
            }
        }

        if (visualizerError) {
            Text(
                "Live meters aren't available for the current output mode.",
                color = Color.White.copy(0.4f),
                fontSize = 12.sp
            )
            return@InstrumentCard
        }

        Text("FFT SPECTRUM", color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            fftBars.forEach { v ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(v.coerceIn(0.03f, 1f))
                        .background(InspectorAccent.copy(alpha = 0.8f), RoundedCornerShape(1.dp))
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LiveMeterStat("PEAK (approx.)", "%.1f dB".format(peakDb))
            LiveMeterStat("RMS (approx.)", "%.1f dB".format(rmsDb))
            LiveMeterStat("PHASE", "%.2f".format(correlation))
            LiveMeterStat("LUFS (Overall)", quality?.let { "%.1f".format(it.lufs) } ?: "—")
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Phase is a correlation proxy from a mixed-down capture — this device doesn't expose true per-channel L/R here.",
            color = Color.White.copy(0.35f),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun LiveMeterStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text(label, color = InspectorAccent.copy(0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// 6. Metadata card
// ---------------------------------------------------------------------------
@Composable
private fun MetadataCard(song: Song) {
    InstrumentCard(label = "METADATA") {
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
    InstrumentCard(label = "REPLAYGAIN") {
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
    InstrumentCard(label = "CODEC INFORMATION") {
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
    InstrumentCard(label = "EMBEDDED ARTWORK") {
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
                .clip(RoundedCornerShape(12.dp))
        )
    }
}

// ---------------------------------------------------------------------------
// 10. Lyrics card
// ---------------------------------------------------------------------------
@Composable
private fun LyricsCard(song: Song) {
    InstrumentCard(label = "LYRICS") {
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
    InstrumentCard(label = "TAGS") {
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
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
