package com.beatraxus.app.ui.components.dsp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.engine.StemSeparationState
import com.beatraxus.app.engine.StemSeparator
import com.beatraxus.app.engine.StemSeparatorManager
import com.beatraxus.app.model.BUILT_IN_STEM_MIXES
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.STEM_COUNT
import com.beatraxus.app.model.STEM_GAIN_MAX_DB
import com.beatraxus.app.model.STEM_GAIN_MIN_DB
import com.beatraxus.app.model.StemChannel
import com.beatraxus.app.model.activeStemMix
import com.beatraxus.app.model.sanitizeStemGains
import com.beatraxus.app.model.sanitizeStemMask
import com.beatraxus.app.model.stemMixDescription
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.ui.theme.DspPaletteState
import com.beatraxus.app.ui.theme.Icon
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PremiumStemMixerCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val config = uiState.dsp.config
    val pal = DspPaletteState.current
    val accent = pal.accent

    val isModelInstalled = remember(context) { StemSeparator.isModelInstalled(context) }
    val sepState by StemSeparatorManager.state.collectAsState()

    val song = uiState.currentSong
    val songId = song?.id
    val currentSepStatus = remember(sepState, songId, context) {
        StemSeparatorManager.getStatusForSong(context, songId)
    }

    val bypassed = config.bypassAll || config.bitPerfectEnabled
    val gains = sanitizeStemGains(config.stemGainsDb)
    val muteMask = sanitizeStemMask(config.stemMuteMask)
    val soloMask = sanitizeStemMask(config.stemSoloMask)
    val activeMix = config.activeStemMix()
    val amber = Color(0xFFFFB74D)
    val red = Color(0xFFFF5252)

    val (statusText, statusColor) = when {
        bypassed && config.stemMixerEnabled -> "BYPASSED" to amber
        !isModelInstalled -> "PREVIEW" to amber
        !config.stemMixerEnabled -> "OFF" to Color.White.copy(0.45f)
        currentSepStatus is StemSeparationState.Separating -> "SEPARATING ${(currentSepStatus.progress * 100).toInt()}%" to accent
        currentSepStatus is StemSeparationState.Ready -> "READY" to accent
        currentSepStatus is StemSeparationState.Failed -> "ERROR" to red
        else -> "$STEM_COUNT STEMS" to accent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .stemGlassPanel(accent, pal.secondary, 22.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header: title + on/off switch ─────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(0.30f), pal.secondary.copy(0.14f))))
                    .border(1.dp, accent.copy(0.55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Tune, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("STEM MIXER", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.5.sp)
                Text("Split the song. Balance every part.", color = Color.White.copy(0.55f), fontSize = 10.sp)
            }
            PremiumSwitch(
                checked = config.stemMixerEnabled,
                onCheckedChange = { viewModel.setStemMixerEnabled(it) },
                accentColor = accent
            )
        }

        // Everything below softly dims while the mixer is off, but stays editable so a mix can be pre-set.
        Column(
            modifier = Modifier.alpha(if (config.stemMixerEnabled) 1f else 0.6f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Current song + status ─────────────────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CURRENT SONG", color = Color.White.copy(0.55f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        song?.let { "${it.title} - ${it.artist}" } ?: "Nothing playing",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusColor.copy(0.12f))
                        .border(1.dp, statusColor.copy(0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(statusText, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }

            if (!isModelInstalled) {
                Text(
                    "Preview: the splitter model is not bundled yet. Your mix is saved and will apply once it is.",
                    color = amber.copy(0.85f), fontSize = 10.sp, lineHeight = 14.sp
                )
            } else if (config.stemMixerEnabled) {
                when (currentSepStatus) {
                    is StemSeparationState.Separating -> {
                        val pct = (currentSepStatus.progress * 100).toInt()
                        Column(
                            modifier = Modifier.fillMaxWidth().stemSubPanel(accent),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Separating $pct%",
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                StemActionButton("Cancel", Icons.Rounded.Close, red) {
                                    StemSeparatorManager.cancelSeparation()
                                }
                            }
                            LinearProgressIndicator(
                                progress = { currentSepStatus.progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = accent,
                                trackColor = Color.White.copy(0.1f)
                            )
                        }
                    }
                    is StemSeparationState.Failed -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().stemSubPanel(red),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Separation error: ${currentSepStatus.errorText}",
                                    color = red, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                StemActionButton("Retry", Icons.Rounded.Refresh, accent) {
                                    if (song != null) StemSeparatorManager.requestSeparation(context, song)
                                }
                            }
                        }
                    }
                    is StemSeparationState.Ready -> {
                        Text(
                            "Ready: 4 stems separated & cached.",
                            color = accent.copy(0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    else -> {}
                }
            }

            // ── Quick mixes ───────────────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().stemSubPanel(accent),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StemSectionLabel("QUICK MIXES")
                BUILT_IN_STEM_MIXES.map { it.name }.chunked(3).forEach { rowNames ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowNames.forEach { name ->
                            StemMixChip(name, activeMix == name, accent, Modifier.weight(1f)) { viewModel.applyStemMix(name) }
                        }
                        repeat(3 - rowNames.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(stemMixDescription(activeMix), color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp)
            }

            // ── The stems ─────────────────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().stemSubPanel(accent),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) { StemSectionLabel("STEMS  (-12 to +12 dB, double tap = 0)") }
                    StemActionButton("Reset", Icons.Rounded.RestartAlt, accent) { viewModel.resetStemMix() }
                }
                StemChannel.entries.forEachIndexed { i, stem ->
                    val muted = ((muteMask shr i) and 1) == 1
                    val solo = ((soloMask shr i) and 1) == 1
                    val supported = (stem != StemChannel.GUITAR && stem != StemChannel.PIANO)
                    StemRow(
                        stem = stem,
                        color = STEM_COLORS[i],
                        icon = STEM_ICONS[i],
                        gainDb = if (supported) gains[i] else 0f,
                        muted = muted && supported,
                        solo = solo && supported,
                        silent = !supported || muted || (soloMask != 0 && !solo),
                        enabled = supported,
                        onGain = { if (supported) viewModel.setStemGain(i, it) },
                        onMute = { if (supported) viewModel.toggleStemMute(i) },
                        onSolo = { if (supported) viewModel.toggleStemSolo(i) }
                    )
                }
            }
        }
    }
}

// ── Stem look ─────────────────────────────────────────────────────────────────────────────────────────────

private val STEM_COLORS = listOf(
    Color(0xFF42A5F5), // Vocals
    Color(0xFFFF7043), // Drums
    Color(0xFF26C6DA), // Bass
    Color(0xFF66BB6A), // Guitar
    Color(0xFFEC407A), // Piano
    Color(0xFF9575CD)  // Other
)

private val STEM_ICONS: List<ImageVector> = listOf(
    Icons.Rounded.Mic,
    Icons.Rounded.Album,
    Icons.Rounded.Speaker,
    Icons.Rounded.MusicNote,
    Icons.Rounded.Piano,
    Icons.Rounded.GraphicEq
)

@Composable
private fun StemRow(
    stem: StemChannel,
    color: Color,
    icon: ImageVector,
    gainDb: Float,
    muted: Boolean,
    solo: Boolean,
    silent: Boolean,
    enabled: Boolean = true,
    onGain: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit
) {
    val valueText = when {
        !enabled -> "N/A"
        muted -> "Muted"
        silent -> "Off"
        abs(gainDb) < 0.05f -> "0.0 dB"
        else -> String.format(java.util.Locale.US, "%+.1f dB", gainDb)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(0.03f))
            .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp)
            .alpha(if (!enabled) 0.35f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).alpha(if (silent && enabled) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(color.copy(0.14f))
                    .border(1.dp, color.copy(0.6f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.width(54.dp)) {
                Text(stem.label.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp, maxLines = 1)
                Text(
                    valueText,
                    color = if (!enabled || silent || abs(gainDb) < 0.05f) Color.White.copy(0.6f) else color,
                    fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false
                )
            }
            StemFader(gainDb, active = enabled && !silent, color, Modifier.weight(1f).height(40.dp), onChange = if (enabled) onGain else { _ -> })
        }
        StemToggle("M", "Mute ${stem.label}", muted, Color(0xFFFF7043), onClick = if (enabled) onMute else { -> })
        StemToggle("S", "Solo ${stem.label}", solo, Color(0xFFFFD54F), onClick = if (enabled) onSolo else { -> })
    }
}

/** Horizontal -12..+12 dB fader. Drag or tap; snaps to 0.5 dB. Double tap resets the stem to 0 dB. */
@Composable
private fun StemFader(valueDb: Float, active: Boolean, color: Color, modifier: Modifier, onChange: (Float) -> Unit) {
    val latestOnChange by rememberUpdatedState(onChange)
    val padPx = with(LocalDensity.current) { 12.dp.toPx() }
    var widthPx by remember { mutableLongStateOf(1L) }
    fun toDb(x: Float): Float {
        val span = (widthPx.toFloat() - 2f * padPx).coerceAtLeast(1f)
        val frac = ((x - padPx) / span).coerceIn(0f, 1f)
        val raw = STEM_GAIN_MIN_DB + frac * (STEM_GAIN_MAX_DB - STEM_GAIN_MIN_DB)
        return (raw * 2f).roundToInt() / 2f
    }
    Canvas(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toLong() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { latestOnChange(0f) },
                    onTap = { latestOnChange(toDb(it.x)) }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latestOnChange(toDb(change.position.x))
                }
            }
    ) {
        val cy = size.height / 2f
        val left = padPx
        val right = size.width - padPx
        val frac = ((valueDb - STEM_GAIN_MIN_DB) / (STEM_GAIN_MAX_DB - STEM_GAIN_MIN_DB)).coerceIn(0f, 1f)
        val thumbX = left + (right - left) * frac
        val zeroX = left + (right - left) * 0.5f
        val tint = if (active) color else Color.White.copy(0.25f)

        // Recessed capsule track with scale ticks
        val capH = 14.dp.toPx()
        drawRoundRect(Color(0xFF050912), Offset(0f, cy - capH / 2), Size(size.width, capH), CornerRadius(capH / 2))
        drawRoundRect(Color.White.copy(0.08f), Offset(0f, cy - capH / 2), Size(size.width, capH), CornerRadius(capH / 2), style = Stroke(1.dp.toPx()))
        for (i in 0..4) {
            val tx = left + (right - left) * i / 4f
            val th = if (i == 2) 5.dp.toPx() else 3.dp.toPx()
            drawLine(Color.White.copy(if (i == 2) 0.35f else 0.14f), Offset(tx, cy - th), Offset(tx, cy + th), 1.dp.toPx())
        }
        // Fill from the 0 dB mark to the thumb
        val fillLeft = minOf(thumbX, zeroX)
        val fillRight = maxOf(thumbX, zeroX)
        val trackH = 5.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(listOf(tint.copy(0.55f), tint), fillLeft, fillRight.coerceAtLeast(fillLeft + 1f)),
            Offset(fillLeft, cy - trackH / 2), Size(fillRight - fillLeft, trackH), CornerRadius(trackH)
        )
        if (active) {
            drawCircle(Brush.radialGradient(listOf(tint.copy(0.35f), Color.Transparent), Offset(thumbX, cy), 20.dp.toPx()), 20.dp.toPx(), Offset(thumbX, cy))
        }
        // Metal fader cap with a neon centre line
        val knobW = 16.dp.toPx()
        val knobH = 28.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF6B7380), Color(0xFF3A404B), Color(0xFF1E222A)), thumbX - knobW / 2, thumbX + knobW / 2),
            Offset(thumbX - knobW / 2, cy - knobH / 2), Size(knobW, knobH), CornerRadius(7.dp.toPx())
        )
        drawLine(tint, Offset(thumbX, cy - knobH / 2 + 4.dp.toPx()), Offset(thumbX, cy + knobH / 2 - 4.dp.toPx()), 2.dp.toPx())
    }
}

@Composable
private fun StemToggle(label: String, description: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 40.dp)
            .clip(shape)
            .background(if (on) color else Color(0xFF0B1424).copy(0.9f))
            .border(1.dp, if (on) color else Color.White.copy(0.12f), shape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color(0xFF0A0A0C) else Color.White.copy(0.8f), fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

// ── Small controls (same recipes as the Surround page) ────────────────────────────────────────────────────

@Composable
private fun StemSectionLabel(text: String) {
    Text(text, color = Color.White.copy(0.55f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun StemMixChip(name: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(38.dp)
            .then(if (selected) Modifier.shadow(8.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (selected) accent.copy(0.18f) else Color(0xFF0B1424).copy(0.9f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else Color.White.copy(0.10f), shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            color = Color.White.copy(if (selected) 1f else 0.75f),
            fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
    }
}

@Composable
private fun StemActionButton(label: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(Color(0xFF0B1424).copy(0.9f))
            .border(1.dp, Color.White.copy(0.12f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White.copy(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun Modifier.stemGlassPanel(primary: Color, secondary: Color, radius: androidx.compose.ui.unit.Dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(Brush.verticalGradient(listOf(Color(0xFF0B1424).copy(alpha = 0.88f), Color(0xFF070B14).copy(alpha = 0.92f))))
    .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.07f), Color.Transparent), radius = 700f))
    .border(
        1.dp,
        Brush.linearGradient(listOf(primary.copy(0.55f), primary.copy(0.10f), secondary.copy(0.45f))),
        RoundedCornerShape(radius)
    )

private fun Modifier.stemSubPanel(accent: Color): Modifier = this
    .clip(RoundedCornerShape(16.dp))
    .background(Brush.verticalGradient(listOf(Color(0xFF050912), Color(0xFF0A1322))))
    .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
    .padding(12.dp)
