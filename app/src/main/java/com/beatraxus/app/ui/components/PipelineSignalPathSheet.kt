package com.beatraxus.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.DspUiState
import com.beatraxus.app.model.EqPhaseMode
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import java.util.Locale
import kotlin.math.abs

enum class StageState {
    UNTOUCHED, PROCESSED, DEGRADED, BYPASSED
}

data class PipelineStage(
    val id: String,
    val title: String,
    val primary: String,
    val details: List<Pair<String, String>>,
    val state: StageState,
    val icon: ImageVector,
    val changeBadge: String? = null
)

enum class PipelineVerdictType(val label: String, val colorHex: Long) {
    BIT_PERFECT("BIT-PERFECT", 0xFF30D158),
    HI_RES_LOSSLESS("HI-RES LOSSLESS", 0xFFFFD700),
    LOSSLESS("LOSSLESS", 0xFF00E5FF),
    PROCESSED("PROCESSED", 0xFFFF9500),
    LOSSY("LOSSY", 0xFF8E8E93)
}

data class PipelineVerdict(
    val type: PipelineVerdictType,
    val summary: String,
    val oneLiner: String
)

data class WireFormat(
    val formatText: String,
    val changeBadge: String? = null
)

data class PipelineResult(
    val stages: List<PipelineStage>,
    val wireFormats: List<WireFormat>,
    val verdict: PipelineVerdict,
    val plainTextSummary: String
)

fun buildPipelineStages(song: Song, uiState: PlayerUiState): PipelineResult {
    val rawCodec = uiState.format.ifBlank { song.format }.ifBlank { "Unknown" }.uppercase(Locale.US)
    val isLossless = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD", "DSF", "PCM").any { rawCodec.contains(it) }
    val dspConf = uiState.dsp.config

    val bitrate = if (uiState.bitrate > 0) uiState.bitrate else song.bitrate
    val inputSr = if (uiState.inputSampleRate > 0) uiState.inputSampleRate else song.sampleRateHz
    val inputBits = if (song.bitDepth > 0) song.bitDepth else if (uiState.bitDepth > 0) uiState.bitDepth else 16
    val outputSr = if (uiState.outputSampleRate > 0) uiState.outputSampleRate else inputSr
    val outputBits = if (uiState.outputBitDepth > 0) uiState.outputBitDepth else inputBits

    val stages = mutableListOf<PipelineStage>()
    val wireFormats = mutableListOf<WireFormat>()

    // 1. SOURCE
    val sourceTitle = "Source File"
    val sourcePrimary = "$rawCodec ${formatBitrate(bitrate)} · $inputBits-bit / ${formatSampleRate(inputSr)}"
    val sourceDetails = mutableListOf(
        "Codec / Container" to rawCodec,
        "Bitrate" to formatBitrate(bitrate),
        "Sample Rate" to formatSampleRate(inputSr),
        "Bit Depth" to "$inputBits-bit",
        "Type" to if (isLossless) "Lossless" else "Lossy"
    )
    if (song.durationMs > 0) {
        val totalSec = song.durationMs / 1000
        sourceDetails.add("Duration" to String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60))
    }
    sourceDetails.add("Source Path" to when (song.source) {
        SongSource.LOCAL -> "Local Storage"
        SongSource.TELEGRAM -> "Telegram Cloud"
        else -> "Cloud Network"
    })

    stages.add(
        PipelineStage(
            id = "source",
            title = sourceTitle,
            primary = sourcePrimary,
            details = sourceDetails,
            state = if (isLossless) StageState.UNTOUCHED else StageState.PROCESSED,
            icon = Icons.Rounded.AudioFile
        )
    )

    // Wire 1: Source -> Decoder
    wireFormats.add(
        WireFormat(
            formatText = "$rawCodec · ${formatSampleRate(inputSr)} · 2ch",
            changeBadge = if (!isLossless) "LOSSY→PCM" else null
        )
    )

    // 2. DECODER
    val isAlac = rawCodec.contains("ALAC")
    val isDsd = rawCodec.contains("DSD") || rawCodec.contains("DSF") || rawCodec.contains("DFF")
    val decoderEngine = when {
        isAlac -> "FFmpeg ALAC Decoder"
        isDsd -> "FFmpeg DSD Decoder"
        rawCodec.contains("FLAC") -> "Android MediaCodec (FLAC)"
        else -> "Android MediaCodec"
    }
    val decoderExec = if (isAlac || isDsd) "Software (FFmpeg)" else "Hardware / System"
    val decoderPrimary = "$decoderEngine · PCM f32 / ${formatSampleRate(inputSr)}"
    stages.add(
        PipelineStage(
            id = "decoder",
            title = "Decoder",
            primary = decoderPrimary,
            details = listOf(
                "Decoder Engine" to decoderEngine,
                "Execution" to decoderExec,
                "Output Format" to "PCM Float 32-bit",
                "Channels" to "2 Channels (Stereo)"
            ),
            state = if (isLossless) StageState.UNTOUCHED else StageState.PROCESSED,
            icon = Icons.Rounded.Memory
        )
    )

    // Wire 2: Decoder -> DSP
    wireFormats.add(WireFormat("PCM f32 · ${formatSampleRate(inputSr)} · 2ch"))

    // 3. DSP CHAIN
    val activeDspModules = mutableListOf<String>()
    val dspTable = mutableListOf<Pair<String, String>>()

    if (dspConf.preampEnabled && abs(dspConf.preampDb) > 0.05f) {
        val valStr = String.format(Locale.US, "%+.1f dB", dspConf.preampDb)
        activeDspModules.add("Preamp $valStr")
        dspTable.add("Preamp" to "ON ($valStr)")
    } else {
        dspTable.add("Preamp" to "OFF (0.0 dB)")
    }

    if (dspConf.eqEnabled) {
        val phaseStr = if (dspConf.eqPhaseMode == EqPhaseMode.LINEAR_PHASE) "Linear Phase" else "Minimum Phase"
        activeDspModules.add("EQ ($phaseStr)")
        dspTable.add("Parametric EQ" to "ON ($phaseStr, ${dspConf.eqBands.size} bands)")
    } else {
        dspTable.add("Parametric EQ" to "OFF")
    }

    if (dspConf.autoEqEnabled && dspConf.autoEqProfile != null) {
        activeDspModules.add("AutoEQ (${dspConf.autoEqProfile.name})")
        dspTable.add("AutoEQ" to "ON (${dspConf.autoEqProfile.name})")
    } else {
        dspTable.add("AutoEQ" to "OFF")
    }

    if (dspConf.bassEnabled && abs(dspConf.bassDb) > 0.05f) {
        activeDspModules.add("Bass")
        dspTable.add("Bass Boost" to String.format(Locale.US, "ON (%+.1f dB)", dspConf.bassDb))
    }

    if (dspConf.crossfeedEnabled) {
        activeDspModules.add("Crossfeed")
        dspTable.add("Crossfeed" to String.format(Locale.US, "ON (Level %.0f%%)", dspConf.crossfeedLevel * 100))
    }

    if (dspConf.spatialAudioEnabled) {
        activeDspModules.add("Spatial (${dspConf.hrtfMode.displayName})")
        dspTable.add("Spatial / HRTF" to "ON (${dspConf.hrtfMode.displayName})")
    }

    if (dspConf.dvcEnabled) {
        activeDspModules.add("DVC (${dspConf.dvcMode.displayName})")
        dspTable.add("Direct Volume Control" to "ON (${dspConf.dvcMode.displayName})")
    }

    if (dspConf.limiterEnabled) {
        val modeStr = if (dspConf.limiterHardModeEnabled) "Hard Clamp" else "Soft Knee"
        activeDspModules.add("Limiter ($modeStr)")
        dspTable.add("Limiter" to String.format(Locale.US, "ON (%s, %.1f dB)", modeStr, dspConf.limiterThresholdDb))
    }

    dspTable.add("Headroom" to String.format(Locale.US, "%.1f dB", uiState.dsp.currentHeadroomDb))

    val isDspActive = activeDspModules.isNotEmpty()
    stages.add(
        PipelineStage(
            id = "dsp",
            title = "Audio DSP",
            primary = if (isDspActive) activeDspModules.joinToString(" · ") else "Bypassed (Bit-Perfect Path)",
            details = dspTable,
            state = if (isDspActive) StageState.PROCESSED else StageState.BYPASSED,
            icon = Icons.Rounded.Tune
        )
    )

    // Wire 3: DSP -> Resampler
    val isResampled = uiState.pipelineResamplerEnabled || inputSr != outputSr
    wireFormats.add(
        WireFormat(
            formatText = "PCM f32 · ${formatSampleRate(if (isResampled) outputSr else inputSr)} · 2ch",
            changeBadge = if (isResampled) "SR" else null
        )
    )

    // 4. RESAMPLER
    val resamplerTypeStr = if (dspConf.highQualityResampler) "SoXR (${dspConf.soxrQuality.displayName})" else "SW Cubic"
    val resamplerPrimary = if (isResampled) {
        "${formatSampleRate(inputSr)} → ${formatSampleRate(outputSr)} ($resamplerTypeStr)"
    } else {
        "Bypassed (${formatSampleRate(inputSr)})"
    }
    stages.add(
        PipelineStage(
            id = "resampler",
            title = "Resampler",
            primary = resamplerPrimary,
            details = listOf(
                "Resampler Engine" to if (isResampled) resamplerTypeStr else "None (Bypassed)",
                "Quality Preset" to dspConf.soxrQuality.displayName,
                "Cutoff Ratio" to String.format(Locale.US, "%.2f", dspConf.resamplerCutoffRatio),
                "Input Sample Rate" to formatSampleRate(inputSr),
                "Output Sample Rate" to formatSampleRate(outputSr)
            ),
            state = when {
                !isResampled -> StageState.BYPASSED
                outputSr >= inputSr -> StageState.PROCESSED
                else -> StageState.DEGRADED
            },
            icon = Icons.Rounded.GraphicEq,
            changeBadge = if (isResampled) "SR" else null
        )
    )

    // Wire 4: Resampler -> Word Length & Dither
    val isBitConverted = inputBits != outputBits
    val ditherType = uiState.dsp.currentDitherType
    val isDitherActive = ditherType != "None" && dspConf.ditherEnabled
    wireFormats.add(
        WireFormat(
            formatText = "PCM $outputBits-bit · ${formatSampleRate(outputSr)} · 2ch",
            changeBadge = if (isBitConverted) "BITS" else null
        )
    )

    // 5. WORD LENGTH & DITHER
    val floatPrecision = if (dspConf.float64Enabled) "Float 64-bit" else "Float 32-bit"
    val wordLengthPrimary = when {
        isBitConverted && isDitherActive -> "$inputBits-bit → $outputBits-bit · Dither: $ditherType"
        isBitConverted -> "$inputBits-bit → $outputBits-bit"
        isDitherActive -> "$outputBits-bit · Dither: $ditherType"
        else -> "$outputBits-bit (Precision: $floatPrecision)"
    }
    stages.add(
        PipelineStage(
            id = "word_length",
            title = "Word Length & Dither",
            primary = wordLengthPrimary,
            details = listOf(
                "Word Length Conversion" to if (isBitConverted) "$inputBits-bit → $outputBits-bit" else "None ($outputBits-bit Direct)",
                "Internal Float Precision" to floatPrecision,
                "Dither Algorithm" to if (isDitherActive) ditherType else "Disabled"
            ),
            state = when {
                !isBitConverted && !isDitherActive -> StageState.BYPASSED
                outputBits < inputBits -> StageState.DEGRADED
                else -> StageState.PROCESSED
            },
            icon = Icons.Rounded.Analytics,
            changeBadge = if (isBitConverted) "BITS" else null
        )
    )

    // Wire 5: Word Length -> Output Engine
    wireFormats.add(WireFormat("$outputBits-bit / ${formatSampleRate(outputSr)} · ${uiState.pipelineOutputPath}"))

    // 6. OUTPUT ENGINE
    val latencyMs = if (outputSr > 0) (uiState.dsp.currentLatencyFrames * 1000f / outputSr) else 0f
    val enginePrimary = "${uiState.pipelineOutputPath} · ${String.format(Locale.US, "%.1f", latencyMs)} ms (${uiState.dsp.currentLatencyFrames} frames)"
    stages.add(
        PipelineStage(
            id = "engine",
            title = "Output Engine",
            primary = enginePrimary,
            details = listOf(
                "Output Driver Path" to uiState.pipelineOutputPath,
                "Buffer Configuration" to "${dspConf.outputBufferMs} ms (${dspConf.outputBufferCount} buffers)",
                "Latency" to String.format(Locale.US, "%.1f ms (%d frames)", latencyMs, uiState.dsp.currentLatencyFrames),
                "Underruns (Buffer Drops)" to "${uiState.underrunCount}"
            ),
            state = when {
                uiState.underrunCount > 0 -> StageState.DEGRADED
                uiState.pipelineOutputPath.contains("HiFi") || uiState.pipelineOutputPath.contains("MMAP") -> StageState.UNTOUCHED
                else -> StageState.PROCESSED
            },
            icon = Icons.Rounded.DeveloperBoard
        )
    )

    // 7. DEVICE
    val deviceName = uiState.dsp.activeOutputDeviceLabel.ifBlank { uiState.outputDevice }
    stages.add(
        PipelineStage(
            id = "device",
            title = "Output Device",
            primary = deviceName,
            details = buildList {
                add("Device Name" to deviceName)
                if (uiState.usbDeviceName.isNotBlank()) {
                    add("USB Exclusive DAC" to uiState.usbDeviceName)
                }
                add("Route Capability" to uiState.hiResCapabilitySummary)
            },
            state = if (uiState.hiResDirectSupported || uiState.usbExclusiveActive) StageState.UNTOUCHED else StageState.PROCESSED,
            icon = Icons.Rounded.SpeakerGroup
        )
    )

    // VERDICT COMPUTATION
    val isBitPerfect = dspConf.bitPerfectEnabled || (!isDspActive && !isResampled && inputBits == outputBits && inputSr == outputSr)

    val verdictType = when {
        isBitPerfect -> PipelineVerdictType.BIT_PERFECT
        isLossless && (inputSr >= 48000 || outputSr >= 48000) && (inputBits >= 24 || outputBits >= 24) -> PipelineVerdictType.HI_RES_LOSSLESS
        isLossless -> PipelineVerdictType.LOSSLESS
        isDspActive || isResampled -> PipelineVerdictType.PROCESSED
        else -> PipelineVerdictType.LOSSY
    }

    val verdictSummary = "$rawCodec $inputBits-bit/${formatSampleRate(inputSr)}" +
            (if (isResampled) " → Resampler" else "") +
            (if (isDspActive) " → DSP" else "") +
            " → $deviceName $outputBits-bit/${formatSampleRate(outputSr)}"

    val oneLiner = when (verdictType) {
        PipelineVerdictType.BIT_PERFECT -> "Unaltered bit-perfect signal path to output hardware."
        PipelineVerdictType.HI_RES_LOSSLESS -> "High-resolution lossless stream rendered through custom audio pipeline."
        PipelineVerdictType.LOSSLESS -> "Lossless audio stream passed cleanly to output engine."
        PipelineVerdictType.PROCESSED -> "Audio signal enhanced by active DSP processing and resampling."
        PipelineVerdictType.LOSSY -> "Standard compressed audio playback."
    }

    val verdict = PipelineVerdict(
        type = verdictType,
        summary = verdictSummary,
        oneLiner = oneLiner
    )

    val plainText = buildString {
        appendLine("=== BEATRAXUS SIGNAL PATH ===")
        appendLine("Verdict: ${verdictType.label}")
        appendLine("Summary: $verdictSummary")
        appendLine("One-liner: $oneLiner")
        appendLine("-----------------------------")
        stages.forEach { stage ->
            appendLine("${stage.title}: ${stage.primary}")
            stage.details.forEach { (k, v) ->
                appendLine("  • $k: $v")
            }
        }
        appendLine("-----------------------------")
        appendLine("Total Latency: ${String.format(Locale.US, "%.1f", latencyMs)} ms")
        appendLine("Headroom: ${String.format(Locale.US, "%.1f", uiState.dsp.currentHeadroomDb)} dB")
        appendLine("Underruns: ${uiState.underrunCount}")
    }

    return PipelineResult(stages, wireFormats, verdict, plainText)
}

private fun formatBitrate(bitrate: Int): String {
    if (bitrate <= 0) return "Unknown bitrate"
    return "${bitrate / 1000} kbps"
}

private fun formatSampleRate(sampleRate: Int): String {
    if (sampleRate <= 0) return "44.1 kHz"
    return if (sampleRate % 1000 == 0) {
        "${sampleRate / 1000}.0 kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", sampleRate / 1000f)
    }
}

@Composable
fun PipelineSignalPathSheet(
    song: Song,
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pipelineResult = remember(song, uiState) { buildPipelineStages(song, uiState) }
    val stages = pipelineResult.stages
    val wireFormats = pipelineResult.wireFormats
    val verdict = pipelineResult.verdict
    val context = LocalContext.current

    val isPlaying = uiState.isPlaying
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clickable(onClick = {}), // consume clicks
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF1B1B22),
                                Color(0xFF121218)
                            )
                        )
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Drag Handle
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                            .align(Alignment.CenterHorizontally)
                    )

                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SIGNAL PATH",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 2.sp,
                                    fontSize = 11.sp
                                ),
                                color = Color.White.copy(alpha = 0.60f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = verdict.summary,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Verdict Pill
                        val pillColor = Color(verdict.type.colorHex)
                        Surface(
                            shape = CircleShape,
                            color = pillColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, pillColor.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = verdict.type.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    fontSize = 10.sp
                                ),
                                color = pillColor
                            )
                        }

                        // Copy Button
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Audio Pipeline", pipelineResult.plainTextSummary)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Pipeline info copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Copy Pipeline Info",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(0.08f))

                    // Stages List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        itemsIndexed(stages) { index, stage ->
                            val wireFormat = wireFormats.getOrNull(index)
                            CompactStageRow(
                                stage = stage,
                                wireFormat = wireFormat,
                                isLast = index == stages.lastIndex,
                                isPlaying = isPlaying,
                                pulseProgress = pulseProgress,
                                index = index
                            )
                        }
                    }

                    // Bottom Summary Strip
                    HorizontalDivider(color = Color.White.copy(0.08f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column {
                                    Text(
                                        text = "LATENCY",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                    val latencyMs = if (uiState.outputSampleRate > 0) (uiState.dsp.currentLatencyFrames * 1000f / uiState.outputSampleRate) else 0f
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", latencyMs)} ms",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        color = Color.White
                                    )
                                }
                                Column {
                                    Text(
                                        text = "HEADROOM",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", uiState.dsp.currentHeadroomDb)} dB",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        color = Color.White
                                    )
                                }
                                Column {
                                    Text(
                                        text = "UNDERRUNS",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        text = "${uiState.underrunCount}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        color = if (uiState.underrunCount > 0) Color(0xFFFF5252) else Color.White
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = verdict.oneLiner,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactStageRow(
    stage: PipelineStage,
    wireFormat: WireFormat?,
    isLast: Boolean,
    isPlaying: Boolean,
    pulseProgress: Float,
    index: Int
) {
    var isExpanded by remember { mutableStateOf(false) }

    val stateColor = when (stage.state) {
        StageState.UNTOUCHED -> Color(0xFF30D158)
        StageState.PROCESSED -> Color(0xFFFF9500)
        StageState.DEGRADED -> Color(0xFFFF453A)
        StageState.BYPASSED -> Color.White.copy(alpha = 0.35f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .semantics(mergeDescendants = true) {
                contentDescription = "${stage.title}: ${stage.primary}, state ${stage.state.name}"
            }
    ) {
        // Rail Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (stage.state == StageState.BYPASSED) stateColor.copy(alpha = 0.4f) else stateColor, CircleShape)
                    .border(1.5.dp, Color(0xFF1B1B22), CircleShape)
            )

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .width(2.dp)
                        .drawBehind {
                            val lineCol = Color.White.copy(alpha = 0.12f)
                            drawLine(
                                color = lineCol,
                                start = Offset(size.width / 2, 0f),
                                end = Offset(size.width / 2, size.height),
                                strokeWidth = 2.dp.toPx()
                            )
                            if (isPlaying && stage.state != StageState.BYPASSED) {
                                val pulseY = size.height * pulseProgress
                                drawCircle(
                                    color = stateColor.copy(alpha = 0.7f),
                                    radius = 2.5.dp.toPx(),
                                    center = Offset(size.width / 2, pulseY)
                                )
                            }
                        }
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // Content Card
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 10.dp)
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = stage.icon,
                        contentDescription = null,
                        tint = stateColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stage.title.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 10.sp
                        ),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                if (wireFormat?.changeBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = stateColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = wireFormat.changeBadge,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = stateColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = stage.primary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (wireFormat != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Wire: ${wireFormat.formatText}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    ),
                    color = Color.White.copy(alpha = 0.45f)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(color = Color.White.copy(0.06f))
                    stage.details.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun PreviewBitPerfectFlac() {
    val song = Song(
        id = "1", uri = Uri.EMPTY, title = "High Voltage", artist = "AC/DC", album = "High Voltage",
        durationMs = 243000, format = "FLAC", sampleRateHz = 96000, bitDepth = 24, bitrate = 2800000
    )
    val uiState = PlayerUiState(
        format = "FLAC", inputSampleRate = 96000, outputSampleRate = 96000,
        bitDepth = 24, outputBitDepth = 24, pipelineOutputPath = "MMAP Exclusive",
        pipelineResamplerEnabled = false, outputDevice = "USB DAC (FiiO KA3)",
        usbDeviceName = "FiiO KA3", hiResDirectSupported = true,
        dsp = DspUiState(
            config = DspConfig(bitPerfectEnabled = true),
            currentHeadroomDb = 0f, currentLatencyFrames = 128, currentDitherType = "None"
        )
    )
    PipelineSignalPathSheet(song = song, uiState = uiState, onDismiss = {})
}

@Preview
@Composable
fun PreviewProcessedMp3() {
    val song = Song(
        id = "2", uri = Uri.EMPTY, title = "Synthetic Soul", artist = "Synth Wave", album = "Retrowave",
        durationMs = 210000, format = "MP3", sampleRateHz = 44100, bitDepth = 16, bitrate = 320000
    )
    val uiState = PlayerUiState(
        format = "MP3", inputSampleRate = 44100, outputSampleRate = 96000,
        bitDepth = 16, outputBitDepth = 24, pipelineOutputPath = "AAudio",
        pipelineResamplerEnabled = true, pipelineResamplerType = "SoXR",
        outputDevice = "Bluetooth Headphones",
        dsp = DspUiState(
            config = DspConfig(eqEnabled = true, limiterEnabled = true, dvcEnabled = true),
            currentHeadroomDb = -3.5f, currentLatencyFrames = 1024, currentDitherType = "TPDF"
        )
    )
    PipelineSignalPathSheet(song = song, uiState = uiState, onDismiss = {})
}

@Preview
@Composable
fun PreviewLossySpeaker() {
    val song = Song(
        id = "3", uri = Uri.EMPTY, title = "Podcast Episode", artist = "Talk Radio", album = "Daily Show",
        durationMs = 1800000, format = "AAC", sampleRateHz = 44100, bitDepth = 16, bitrate = 128000
    )
    val uiState = PlayerUiState(
        format = "AAC", inputSampleRate = 44100, outputSampleRate = 44100,
        bitDepth = 16, outputBitDepth = 16, pipelineOutputPath = "AudioTrack",
        pipelineResamplerEnabled = false, outputDevice = "Built-in Speaker",
        dsp = DspUiState(
            config = DspConfig(),
            currentHeadroomDb = 0f, currentLatencyFrames = 256, currentDitherType = "None"
        )
    )
    PipelineSignalPathSheet(song = song, uiState = uiState, onDismiss = {})
}
