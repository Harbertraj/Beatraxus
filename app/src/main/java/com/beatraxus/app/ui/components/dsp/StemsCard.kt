package com.beatraxus.app.ui.components.dsp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.StemChannel
import com.beatraxus.app.model.StemChannelState
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

// Same accent used throughout DspScreen.kt
private val StemsAccent = Color(0xFF00F2FF)

private fun stemIcon(channel: StemChannel): ImageVector = when (channel) {
    StemChannel.VOCALS -> Icons.Rounded.Mic
    StemChannel.DRUMS -> Icons.Rounded.Album
    StemChannel.BASS -> Icons.Rounded.Piano
    StemChannel.OTHER -> Icons.Rounded.MusicNote
}

/**
 * Moises-style stem mixer: lets the user isolate/mute vocals, drums, bass, and
 * "other" instruments independently for the currently playing track.
 *
 * NOTE ON SCOPE: real AI stem separation (what actually produces the isolated
 * vocals/drums/bass/other audio, the way Moises does) requires an on-device or
 * cloud source-separation model (e.g. a Demucs-family neural net) that this
 * project does not currently include. This screen is fully wired end-to-end —
 * state, mute/solo/volume logic, persistence hook — and is ready to drive real
 * per-stem gains the moment such a separation pipeline exists. Until then,
 * `stemSourceReady` stays false and the UI clearly labels itself as pending
 * separation instead of silently pretending to isolate audio it can't.
 */
@Composable
fun PremiumStemsCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val isBypassed = config.bitPerfectEnabled
    val anySoloed = config.stemStates.values.any { it.soloed }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Layers, null, tint = StemsAccent, modifier = Modifier.size(16.dp))
                Text(
                    "STEM MIXER",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
            }

            if (isBypassed) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("BYPASSED", color = Color.White.copy(0.3f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            } else {
                PremiumSwitch(
                    checked = config.stemMixerEnabled,
                    onCheckedChange = { viewModel.setStemMixerEnabled(it) }
                )
            }
        }

        // Separation status banner
        if (!config.stemSourceReady) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(0.04f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.Info, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(16.dp))
                Text(
                    "Isolating vocals, drums, bass & other instruments for this track requires AI stem separation, which isn't wired up yet on this build. The mixer below is ready to go the moment separated stems are available for a song.",
                    color = Color.White.copy(0.45f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        val mixerActive = config.stemMixerEnabled && !isBypassed

        // Per-instrument rows
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StemChannel.entries.forEach { channel ->
                val state = config.stemStates[channel] ?: StemChannelState()
                StemChannelRow(
                    channel = channel,
                    state = state,
                    enabled = mixerActive,
                    dimmedBySolo = anySoloed && !state.soloed,
                    onMuteToggle = { viewModel.toggleStemMute(channel) },
                    onSoloToggle = { viewModel.toggleStemSolo(channel) },
                    onVolumeChange = { viewModel.setStemVolume(channel, it) }
                )
            }
        }

        // Footer actions + stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { viewModel.resetStemMixer() },
                enabled = mixerActive,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(40.dp),
                color = Color.White.copy(0.04f),
                border = BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.RestartAlt, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("RESET MIX", color = Color.White.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val mutedCount = config.stemStates.values.count { it.muted }
            StatChip("MUTED", "$mutedCount/${StemChannel.entries.size}")
            StatChip("MODE", if (anySoloed) "SOLO" else "MIX")
            StatChip("STATUS", if (isBypassed) "BYPASSED" else if (mixerActive) "ACTIVE" else "OFF")
        }
    }
}

@Composable
private fun StemChannelRow(
    channel: StemChannel,
    state: StemChannelState,
    enabled: Boolean,
    dimmedBySolo: Boolean,
    onMuteToggle: () -> Unit,
    onSoloToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    val effectivelyOff = state.muted || dimmedBySolo
    val rowAlpha = if (!enabled) 0.35f else if (effectivelyOff) 0.5f else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.03f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    stemIcon(channel),
                    null,
                    tint = (if (effectivelyOff) Color.White.copy(0.3f) else StemsAccent).copy(alpha = if (enabled) 1f else 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    channel.displayName.uppercase(),
                    color = Color.White.copy(rowAlpha),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StemToggleChip(
                    label = "SOLO",
                    active = state.soloed,
                    enabled = enabled,
                    onClick = onSoloToggle
                )
                StemToggleChip(
                    label = "MUTE",
                    active = state.muted,
                    enabled = enabled,
                    onClick = onMuteToggle
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                StemVolumeSlider(
                    value = state.volume,
                    enabled = enabled && !effectivelyOff,
                    onValueChange = onVolumeChange
                )
            }
            Text(
                "${(state.volume * 100).roundToInt()}%",
                color = Color.White.copy(if (enabled) 0.6f else 0.3f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(34.dp)
            )
        }
    }
}

@Composable
private fun StemToggleChip(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (active) StemsAccent.copy(alpha = 0.18f) else Color.White.copy(0.05f)
    val fg = if (active) StemsAccent else Color.White.copy(0.4f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(1.dp, if (active) StemsAccent.copy(0.4f) else Color.White.copy(0.08f))
    ) {
        Text(
            label,
            color = fg,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun StemVolumeSlider(value: Float, enabled: Boolean, onValueChange: (Float) -> Unit) {
    var internalValue by remember(value) { mutableStateOf(value) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(0.08f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(internalValue.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(StemsAccent.copy(0.3f), if (enabled) StemsAccent else Color.White.copy(0.2f))
                    )
                )
        )
        Slider(
            value = internalValue,
            onValueChange = {
                internalValue = it
                onValueChange(it)
            },
            onValueChangeFinished = { onValueChange(internalValue) },
            valueRange = 0f..1f,
            enabled = enabled,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledThumbColor = Color.Gray,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

