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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.DspUiState
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.EqPhaseMode
import java.util.Locale

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

fun buildPipelineStages(song: Song, uiState: PlayerUiState): List<PipelineStage> {
    val codec = uiState.format.ifBlank { song.format }.ifBlank { "Unknown" }.uppercase(Locale.US)
    val isLossless = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD", "DSF", "PCM").any { codec.contains(it) }
    
    val stages = mutableListOf<PipelineStage>()

    // 1. SOURCE
    val bitrate = if (uiState.bitrate > 0) uiState.bitrate else song.bitrate
    val inputSr = if (uiState.inputSampleRate > 0) uiState.inputSampleRate else song.sampleRateHz
    val inputSrLabel = formatPipelineSampleRate(inputSr)
    val inputBits = if (song.bitDepth > 0) song.bitDepth else if (uiState.bitDepth > 0) uiState.bitDepth else 16
    
    stages.add(
        PipelineStage(
            id = "source",
            title = "Source File",
            primary = "$codec ${formatPipelineBitrate(bitrate)}",
            details = listOf(
                "Codec" to codec,
                "Bitrate" to formatPipelineBitrate(bitrate),
                "Sample Rate" to inputSrLabel,
                "Bit Depth" to "$inputBits-bit",
                "Type" to if (isLossless) "Lossless" else "Lossy"
            ),
            state = StageState.UNTOUCHED,
            icon = Icons.Rounded.AudioFile
        )
    )

    // 2. DECODER
    val isAlac = codec.contains("ALAC")
    val decoderName = if (isAlac) "FFmpeg ALAC Decoder" else "Android MediaCodec"
    val outputSr = if (uiState.outputSampleRate > 0) uiState.outputSampleRate else inputSr
    val outputBits = if (uiState.outputBitDepth > 0) uiState.outputBitDepth else inputBits
    
    stages.add(
        PipelineStage(
            id = "decoder",
            title = "Decoder",
            primary = "PCM $inputBits-bit / $inputSrLabel",
            details = listOf(
                "Engine" to decoderName,
                "Format" to "PCM",
                "Channels" to "Stereo"
            ),
            state = if (isLossless) StageState.UNTOUCHED else StageState.PROCESSED,
            icon = Icons.Rounded.Memory
        )
    )

    // 3. DSP
    val dspConf = uiState.dsp.config
    val activeDsp = buildList {
        if (dspConf.eqEnabled) add(if (dspConf.eqPhaseMode == EqPhaseMode.LINEAR_PHASE) "EQ (LP)" else "EQ")
        if (dspConf.autoEqEnabled && dspConf.autoEqProfile != null) add("AutoEQ")
        if (dspConf.bassEnabled) add("Bass")
        if (dspConf.trebleEnabled) add("Treble")
        if (dspConf.crossfeedEnabled) add("Crossfeed")
        if (dspConf.spatialAudioEnabled) add("Spatial")
        if (dspConf.soundStageEnabled) add("Soundstage")
        if (dspConf.limiterEnabled) add("Limiter")
        if (dspConf.dvcEnabled) add("DVC")
    }
    
    val isDspActive = activeDsp.isNotEmpty()
    val dspDetails = mutableListOf<Pair<String, String>>()
    if (dspConf.autoEqEnabled && dspConf.autoEqProfile != null) {
        dspDetails.add("AutoEQ" to dspConf.autoEqProfile.name)
    }
    if (dspConf.eqEnabled) {
        dspDetails.add("EQ Mode" to dspConf.eqPhaseMode.displayName)
    }
    dspDetails.add("Headroom" to "${uiState.dsp.currentHeadroomDb} dB")
    
    stages.add(
        PipelineStage(
            id = "dsp",
            title = "Audio DSP",
            primary = if (isDspActive) activeDsp.joinToString(" • ") else "Bypassed",
            details = dspDetails,
            state = if (isDspActive) StageState.PROCESSED else StageState.BYPASSED,
            icon = Icons.Rounded.Tune
        )
    )

    // 4. RESAMPLER
    val isResampled = uiState.pipelineResamplerEnabled || inputSr != outputSr
    val outputSrLabel = formatPipelineSampleRate(outputSr)
    
    stages.add(
        PipelineStage(
            id = "resampler",
            title = "Resampler",
            primary = if (isResampled) "$inputSrLabel → $outputSrLabel" else "Bypassed",
            details = listOf(
                "Type" to if (isResampled) uiState.pipelineResamplerType else "None"
            ),
            state = when {
                !isResampled -> StageState.BYPASSED
                outputSr > inputSr -> StageState.PROCESSED
                else -> StageState.DEGRADED
            },
            icon = Icons.Rounded.GraphicEq,
            changeBadge = if (isResampled) "SR" else null
        )
    )

    // 5. BIT-DEPTH CONVERSION / DITHER
    val isBitConverted = inputBits != outputBits
    val ditherType = uiState.dsp.currentDitherType
    val ditherActive = ditherType != "None" && dspConf.ditherEnabled
    
    stages.add(
        PipelineStage(
            id = "bit_depth",
            title = "Word Length & Dither",
            primary = if (isBitConverted) "$inputBits-bit → $outputBits-bit" else "Bypassed",
            details = listOf(
                "Dither" to if (ditherActive) ditherType else "None"
            ),
            state = when {
                !isBitConverted && !ditherActive -> StageState.BYPASSED
                outputBits < inputBits -> StageState.DEGRADED
                else -> StageState.PROCESSED
            },
            icon = Icons.Rounded.Analytics,
            changeBadge = if (isBitConverted) "BITS" else null
        )
    )

    // 6. OUTPUT ENGINE
    val latencyMs = if (outputSr > 0) (uiState.dsp.currentLatencyFrames * 1000f / outputSr) else 0f
    
    stages.add(
        PipelineStage(
            id = "engine",
            title = "Output Engine",
            primary = uiState.pipelineOutputPath,
            details = listOf(
                "Latency" to "${String.format(Locale.US, "%.1f", latencyMs)} ms (${uiState.dsp.currentLatencyFrames} frames)"
            ),
            state = StageState.UNTOUCHED,
            icon = Icons.Rounded.DeveloperBoard
        )
    )

    // 7. DEVICE
    stages.add(
        PipelineStage(
            id = "device",
            title = "Output Device",
            primary = uiState.dsp.activeOutputDeviceLabel.ifBlank { uiState.outputDevice },
            details = buildList {
                if (uiState.usbDeviceName.isNotBlank()) add("USB Device" to uiState.usbDeviceName)
                add("Hi-Res" to uiState.hiResCapabilitySummary)
            },
            state = StageState.UNTOUCHED,
            icon = Icons.Rounded.SpeakerGroup
        )
    )

    return stages
}

private fun formatPipelineBitrate(bitrate: Int): String {
    if (bitrate <= 0) return "Unknown"
    return "${bitrate / 1000} kbps"
}

private fun formatPipelineSampleRate(sampleRate: Int): String {
    if (sampleRate <= 0) return "Unknown"
    return if (sampleRate % 1000 == 0) {
        "${sampleRate / 1000} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", sampleRate / 1000f)
    }
}

fun computeVerdict(song: Song, uiState: PlayerUiState, stages: List<PipelineStage>): Pair<String, String> {
    val codec = uiState.format.ifBlank { song.format }.ifBlank { "Unknown" }.uppercase(Locale.US)
    val isLossless = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD", "DSF", "PCM").any { codec.contains(it) }
    val hasDsp = stages.any { it.id == "dsp" && it.state == StageState.PROCESSED }
    val hasResampling = stages.any { it.id == "resampler" && it.state != StageState.BYPASSED }
    val outputBits = uiState.outputBitDepth.takeIf { it > 0 } ?: song.bitDepth
    val outputSr = uiState.outputSampleRate.takeIf { it > 0 } ?: song.sampleRateHz
    val isBitPerfect = uiState.dsp.config.bitPerfectEnabled || (!hasDsp && !hasResampling && outputBits == song.bitDepth && outputSr == song.sampleRateHz)

    val verdict = when {
        isBitPerfect -> "BIT-PERFECT"
        isLossless && outputSr >= 48000 && outputBits >= 24 -> "HI-RES LOSSLESS"
        isLossless -> "LOSSLESS"
        else -> "LOSSY"
    }

    val subtitle = if (isBitPerfect) {
        "Bit-perfect path, no processing"
    } else if (hasDsp && hasResampling) {
        "Signal is modified by EQ and resampling"
    } else if (hasDsp) {
        "Signal is processed by DSP"
    } else if (hasResampling) {
        "Signal is resampled"
    } else {
        "Standard playback"
    }

    return verdict to subtitle
}

@Composable
fun PipelineSignalPathSheet(
    song: Song,
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stages = remember(song, uiState) { buildPipelineStages(song, uiState) }
    val verdictData = remember(song, uiState, stages) { computeVerdict(song, uiState, stages) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clickable(onClick = {}), // Consume clicks
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF1E1E26).copy(alpha = 0.95f),
                                Color(0xFF121218).copy(alpha = 0.98f)
                            )
                        )
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Drag Handle
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .align(Alignment.CenterHorizontally)
                    )

                    // Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SIGNAL PATH",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp
                            ),
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Quality Pill
                        val pillColor = when (verdictData.first) {
                            "BIT-PERFECT" -> Color(0xFF30D158)
                            "HI-RES LOSSLESS" -> Color(0xFFFFD700)
                            "LOSSLESS" -> Color(0xFF00E5FF)
                            else -> Color(0xFFC0C0C0)
                        }
                        
                        Surface(
                            shape = CircleShape,
                            color = pillColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, pillColor.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = verdictData.first,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = pillColor
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = verdictData.second,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(0.05f))

                    // Main Timeline
                    val isPlaying = uiState.isPlaying
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(stages) { index, stage ->
                            StageCard(
                                stage = stage,
                                isLast = index == stages.lastIndex,
                                isPlaying = isPlaying,
                                index = index
                            )
                        }
                    }

                    // Bottom Summary
                    HorizontalDivider(color = Color.White.copy(0.05f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.2f))
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "LATENCY",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            val latencyMs = if (uiState.outputSampleRate > 0) (uiState.dsp.currentLatencyFrames * 1000f / uiState.outputSampleRate) else 0f
                            Text(
                                text = "${String.format(Locale.US, "%.1f", latencyMs)} ms",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color.White
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "HEADROOM",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "${uiState.dsp.currentHeadroomDb} dB",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StageCard(
    stage: PipelineStage,
    isLast: Boolean,
    isPlaying: Boolean,
    index: Int
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    // Staggered Entrance Animation
    val transitionState = remember { MutableTransitionState(false).apply { targetState = true } }
    val enterTransition = slideInVertically(
        initialOffsetY = { 20 },
        animationSpec = tween(durationMillis = 300, delayMillis = index * 40, easing = LinearOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = 300, delayMillis = index * 40)
    )

    val color = when (stage.state) {
        StageState.UNTOUCHED -> Color(0xFF30D158)
        StageState.PROCESSED -> Color(0xFFFFD60A)
        StageState.DEGRADED -> Color(0xFFFF453A)
        StageState.BYPASSED -> Color.White.copy(alpha = 0.3f)
    }

    AnimatedVisibility(
        visibleState = transitionState,
        enter = enterTransition
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 4.dp)
                .clickable { isExpanded = !isExpanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = "${stage.title}, ${stage.primary}, ${stage.state.name.lowercase(Locale.US)}"
                }
        ) {
            // Left Connector Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(if (stage.state == StageState.BYPASSED) color.copy(alpha = 0.5f) else color, CircleShape)
                        .border(2.dp, Color(0xFF1E1E26), CircleShape)
                )
                
                if (!isLast) {
                    val lineColor = Color.White.copy(alpha = 0.1f)
                    val pulseAnim = rememberInfiniteTransition(label = "pulse")
                    val pulsePos by pulseAnim.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "pulsePos"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .width(2.dp)
                            .drawBehind {
                                drawLine(
                                    color = lineColor,
                                    start = Offset(size.width / 2, 0f),
                                    end = Offset(size.width / 2, size.height),
                                    strokeWidth = 2.dp.toPx()
                                )
                                if (isPlaying && stage.state != StageState.BYPASSED) {
                                    val y = size.height * pulsePos
                                    drawCircle(
                                        color = color.copy(alpha = 0.6f),
                                        radius = 3.dp.toPx(),
                                        center = Offset(size.width / 2, y)
                                    )
                                }
                            }
                    ) {
                        if (stage.changeBadge != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(Color(0xFF2C2C35), RoundedCornerShape(4.dp))
                                    .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stage.changeBadge,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                    color = color
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Content Card
            val cardAlpha = if (stage.state == StageState.BYPASSED) 0.5f else 1f
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 24.dp)
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = stage.icon,
                        contentDescription = null,
                        tint = color.copy(alpha = cardAlpha),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stage.title.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White.copy(alpha = cardAlpha * 0.7f)
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = stage.primary,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = if (stage.primary.any { it.isDigit() }) FontFamily.Monospace else FontFamily.Default
                    ),
                    color = Color.White.copy(alpha = cardAlpha)
                )

                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        stage.details.forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = cardAlpha * 0.5f)
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = if (value.any { it.isDigit() }) FontFamily.Monospace else FontFamily.Default
                                    ),
                                    color = Color.White.copy(alpha = cardAlpha * 0.9f)
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
        id = "1", uri = Uri.EMPTY, title = "Test", artist = "Artist", album = "Album",
        durationMs = 1000, format = "FLAC", sampleRateHz = 44100, bitDepth = 16, bitrate = 800000
    )
    val uiState = PlayerUiState(
        format = "FLAC", inputSampleRate = 44100, outputSampleRate = 44100,
        bitDepth = 16, outputBitDepth = 16, pipelineOutputPath = "AAudio",
        pipelineResamplerEnabled = false,
        dsp = DspUiState(
            currentHeadroomDb = 0f, currentLatencyFrames = 192, currentDitherType = "None"
        )
    )
    PipelineSignalPathSheet(song, uiState, {})
}

@Preview
@Composable
fun PreviewProcessedMp3() {
    val song = Song(
        id = "2", uri = Uri.EMPTY, title = "Test", artist = "Artist", album = "Album",
        durationMs = 1000, format = "MP3", sampleRateHz = 44100, bitDepth = 16, bitrate = 320000
    )
    val uiState = PlayerUiState(
        format = "MP3", inputSampleRate = 44100, outputSampleRate = 48000,
        bitDepth = 16, outputBitDepth = 16, pipelineOutputPath = "AudioTrack",
        pipelineResamplerEnabled = true, pipelineResamplerType = "SOXR",
        dsp = DspUiState(
            config = DspConfig(eqEnabled = true),
            currentHeadroomDb = -3.5f, currentLatencyFrames = 1024, currentDitherType = "TPDF"
        )
    )
    PipelineSignalPathSheet(song, uiState, {})
}
