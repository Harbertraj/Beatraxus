package com.beatraxus.app.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.DspUiState
import com.beatraxus.app.model.EqPhaseMode
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.ui.utils.DialogBlurBehind
import com.beatraxus.app.ui.utils.rememberWindowBlurSupported
import java.util.Locale
import kotlin.math.abs

private const val PULSE_PERIOD_MS = 2200     // one slow, calm packet pass per connector
private val PACKET_TAIL = 16.dp              // length of the fading comet tail
private val RAIL_WIDTH = 22.dp
private val LIST_H_PAD = 14.dp
private val LIST_V_PAD = 10.dp
private val NODE_CENTER_Y = 17.dp // spacer(10) + half of 14dp node

private fun stageStateColor(state: StageState): Color = when (state) {
    StageState.UNTOUCHED -> Color(0xFF30D158)
    StageState.PROCESSED -> Color(0xFFFF9500)
    StageState.DEGRADED -> Color(0xFFFF453A)
    StageState.BYPASSED -> Color.White.copy(alpha = 0.35f)
}

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
    modifier: Modifier = Modifier,
    /**
     * true  -> the OS blurs the screen behind the dialog window (library / mini-player use).
     * false -> the caller already blurs its own content (NowPlayingScreen does), so we only
     *          remove the dark dim and avoid a double blur.
     */
    windowBlur: Boolean = true
) {
    val pipelineResult = remember(song, uiState) { buildPipelineStages(song, uiState) }
    val stages = pipelineResult.stages
    val wireFormats = pipelineResult.wireFormats
    val verdict = pipelineResult.verdict
    val verdictColor = Color(verdict.type.colorHex)

    val isPlaying = uiState.isPlaying
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    // Only when nothing can blur the background do we fall back to a light scrim,
    // so the card stays readable on very old devices. Normal case = NO dark shade.
    val blurWorks = if (windowBlur) rememberWindowBlurSupported()
    else Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogBlurBehind(radiusDp = if (windowBlur) 22 else 0)

        Box(
            modifier = modifier
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

            // Compact "Poweramp-size" card: ~90% width (max 400dp), wraps its content,
            // never taller than ~74% of the screen.
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .widthIn(max = 400.dp)
                    .heightIn(max = screenHeight * 0.74f)
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
                // Verdict-coloured glow line on top of the card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, verdictColor.copy(alpha = 0.9f), Color.Transparent)
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(verdictColor.copy(alpha = 0.16f), CircleShape)
                                .border(1.dp, verdictColor.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = verdictColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SIGNAL PATH",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.8.sp,
                                    fontSize = 13.sp
                                ),
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = verdict.summary,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                ),
                                color = Color.White.copy(alpha = 0.65f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Verdict pill
                    Surface(
                        shape = CircleShape,
                        color = verdictColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = verdict.type.label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                fontSize = 10.sp
                            ),
                            color = verdictColor,
                            maxLines = 1
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(0.08f))

                // Stages list (wraps content, scrolls only if the card hits its max height).
                // ONE continuous rail line + travelling dot is drawn behind the whole list so the
                // dot never skips the gaps where the wire-format chips sit.
                val listScroll = rememberScrollState()
                // Measured height of each stage group (card + wire-format chip). A group's height
                // is exactly the length of the connector that leaves that stage's node.
                val groupHeights = remember(stages.size) {
                    mutableStateListOf<Int>().apply { repeat(stages.size) { add(0) } }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(listScroll)
                        .drawBehind {
                            val x = (LIST_H_PAD + RAIL_WIDTH / 2).toPx()
                            val stroke = 2.dp.toPx()
                            var top = LIST_V_PAD.toPx()
                            for (i in 0 until stages.lastIndex) {
                                val h = groupHeights.getOrElse(i) { 0 }.toFloat()
                                if (h <= 0f) continue
                                val y0 = top + NODE_CENTER_Y.toPx()
                                val y1 = y0 + h
                                val st = stages[i].state
                                val bypassed = st == StageState.BYPASSED
                                val c = stageStateColor(st)

                                if (bypassed) {
                                    // Bypassed stage: dim dashed connector, no travelling dots.
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.10f),
                                        start = Offset(x, y0),
                                        end = Offset(x, y1),
                                        strokeWidth = stroke,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 6.dp.toPx()))
                                    )
                                } else {
                                    drawLine(
                                        color = c.copy(alpha = 0.22f),
                                        start = Offset(x, y0),
                                        end = Offset(x, y1),
                                        strokeWidth = stroke
                                    )
                                    if (isPlaying) {
                                        // ONE slim "data packet" per connector: a tapered comet streak
                                        // with a tiny bright head, easing along the wire. Each stage is
                                        // phase-offset so the signal appears to hand off down the chain.
                                        val tail = PACKET_TAIL.toPx()
                                        val phase = (pulseProgress + i * 0.18f) % 1f
                                        val eased = phase * phase * (3f - 2f * phase) // smoothstep
                                        val travel = h + tail
                                        val headY = y0 + eased * travel
                                        val tailY = headY - tail
                                        val a = maxOf(tailY, y0)
                                        val b = minOf(headY, y1)
                                        if (b > a) {
                                            // fade in/out near the node ends so it never pops
                                            val edge = 10.dp.toPx()
                                            val fade = minOf(
                                                (headY - y0) / edge,
                                                (y1 + tail - headY) / edge,
                                                1f
                                            ).coerceIn(0f, 1f)
                                            drawLine(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        c.copy(alpha = 0f),
                                                        c.copy(alpha = 0.85f * fade)
                                                    ),
                                                    startY = tailY,
                                                    endY = headY
                                                ),
                                                start = Offset(x, a),
                                                end = Offset(x, b),
                                                strokeWidth = 2.dp.toPx(),
                                                cap = StrokeCap.Round
                                            )
                                            if (headY <= y1) {
                                                drawCircle(
                                                    color = Color.White.copy(alpha = 0.9f * fade),
                                                    radius = 1.4.dp.toPx(),
                                                    center = Offset(x, headY)
                                                )
                                            }
                                        }
                                    }
                                }
                                top += h
                            }
                        }
                        .padding(horizontal = LIST_H_PAD, vertical = LIST_V_PAD)
                ) {
                    stages.forEachIndexed { index, stage ->
                        CompactStageRow(
                            stage = stage,
                            wireFormat = wireFormats.getOrNull(index),
                            isLast = index == stages.lastIndex,
                            modifier = Modifier.onSizeChanged {
                                if (index < groupHeights.size && groupHeights[index] != it.height) {
                                    groupHeights[index] = it.height
                                }
                            }
                        )
                    }
                }

                // Bottom summary strip
                HorizontalDivider(color = Color.White.copy(0.08f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    val statSr = when {
                        uiState.outputSampleRate > 0 -> uiState.outputSampleRate
                        uiState.inputSampleRate > 0 -> uiState.inputSampleRate
                        else -> 44100
                    }
                    val latencyMs = uiState.dsp.currentLatencyFrames * 1000f / statSr

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SummaryStat(
                            label = "LATENCY",
                            value = "${String.format(Locale.US, "%.1f", latencyMs)} ms",
                            valueColor = Color.White
                        )
                        SummaryStat(
                            label = "HEADROOM",
                            value = "${String.format(Locale.US, "%.1f", uiState.dsp.currentHeadroomDb)} dB",
                            valueColor = Color.White
                        )
                        SummaryStat(
                            label = "UNDERRUNS",
                            value = "${uiState.underrunCount}",
                            valueColor = if (uiState.underrunCount > 0) Color(0xFFFF5252) else Color.White
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = verdict.oneLiner,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, valueColor: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            ),
            color = Color.White.copy(alpha = 0.5f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            color = valueColor
        )
    }
}

@Composable
fun CompactStageRow(
    stage: PipelineStage,
    wireFormat: WireFormat?,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    val stateColor = when (stage.state) {
        StageState.UNTOUCHED -> Color(0xFF30D158)
        StageState.PROCESSED -> Color(0xFFFF9500)
        StageState.DEGRADED -> Color(0xFFFF453A)
        StageState.BYPASSED -> Color.White.copy(alpha = 0.35f)
    }

    val stateLabel = when (stage.state) {
        StageState.UNTOUCHED -> "UNTOUCHED"
        StageState.PROCESSED -> "PROCESSED"
        StageState.DEGRADED -> "DEGRADED"
        StageState.BYPASSED -> "BYPASSED"
    }

    val railWidth = RAIL_WIDTH
    val cardShape = RoundedCornerShape(14.dp)

    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {
                contentDescription = "${stage.title}: ${stage.primary}, state ${stage.state.name}"
            }
    ) {
        // Rail: glowing node only. The connector line + travelling dot are drawn once,
        // continuously, by the parent list so they run through the wire-format chips too.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(stateColor.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (stage.state == StageState.BYPASSED) stateColor.copy(alpha = 0.5f) else stateColor,
                            CircleShape
                        )
                )
            }

        }

        Spacer(Modifier.width(8.dp))

        // Content card
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp)
                .background(Color.White.copy(alpha = 0.04f), cardShape)
                .border(1.dp, Color.White.copy(alpha = 0.07f), cardShape)
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
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stage.title.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontSize = 10.5.sp
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.4.sp
                        ),
                        color = stateColor.copy(alpha = 0.85f)
                    )

                    if (stage.changeBadge != null) {
                        Spacer(Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = stateColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = stage.changeBadge,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = stateColor
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = stage.primary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Details are always visible (nothing removed)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                textAlign = TextAlign.End
                            ),
                            color = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                }
            }
        }
    }

    // Wire format chip between blocks (rail keeps running through it)
    if (wireFormat != null && !isLast) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Spacer(Modifier.width(railWidth))

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = wireFormat.formatText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = Color.White.copy(alpha = 0.75f)
                    )

                    if (wireFormat.changeBadge != null) {
                        Spacer(Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = wireFormat.changeBadge,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
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
