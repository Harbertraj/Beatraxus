package com.beatraxus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.BUILT_IN_SURROUND_PRESETS
import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME
import com.beatraxus.app.model.SURROUND_TRIM_MAX_DB
import com.beatraxus.app.model.SURROUND_TRIM_MIN_DB
import com.beatraxus.app.model.SurroundChannel
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.SurroundMode
import com.beatraxus.app.ui.components.dsp.SurroundStageCard
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import kotlin.math.roundToInt

private val SurroundAmber = Color(0xFFFF8F00)
private val SurroundAmberSoft = Color(0xFFFFB347)

/**
 * Surround simulator controls inside the video player. Same settings as Studio DSP > 5.1 / 7.1 (they are shared),
 * reachable at any time from the always-visible top icon.
 *
 * A soundtrack that already is a real 5.1 / 7.1 mix keeps its discrete channels: layout and mode do nothing for it
 * (they only shape the stereo upmix), so they are hidden, and the levels are shown relative to the Default preset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoSurroundSection(
    uiState: VideoPlayerUiState,
    onEnabled: (Boolean) -> Unit,
    onLayout: (SurroundLayout) -> Unit,
    onMode: (SurroundMode) -> Unit,
    onPreset: (String) -> Unit,
    onTrim: (Int, Float) -> Unit,
    onResetTrims: () -> Unit,
    onSaveCustom: () -> Unit
) {
    val settings = uiState.surroundSettings
    // The simulator switch. With "Connect to audio mode" it adds the simulator to the DSP pipeline (ON) or skips it
    // (OFF); without audio mode ON runs the soundtrack through the simulator alone.
    val simulatorOn = uiState.surroundSimulatorEnabled
    val discrete = uiState.surroundDiscrete
    val layout = settings.layout
    // What Auto resolves to for this soundtrack: a real 5.1 / 7.1 mix keeps its own layout, a stereo one stays stereo.
    val autoChannels = when (uiState.surroundSourceChannels) {
        8 -> 8
        6 -> 6
        else -> 2
    }
    // Channel count the speaker preview / level rows are drawn for.
    val shownChannels = when {
        discrete -> uiState.surroundSourceChannels
        layout == SurroundLayout.SEVEN_ONE -> 8
        layout == SurroundLayout.FIVE_ONE -> 6
        layout == SurroundLayout.AUTO -> autoChannels
        else -> 2
    }
    val sevenOne = shownChannels == 8
    val layoutName = when (shownChannels) {
        8 -> "7.1"
        6 -> "5.1"
        else -> "Stereo"
    }
    // What the speaker preview draws. It follows the layout chips straight away. Only two things overrule it: a real
    // 5.1 / 7.1 soundtrack (always drawn with its own channels) and an output the device refused to open wide (the sound
    // really is stereo, so the room shows stereo, like the Studio DSP surround page does).
    val outputRefusedWide = !discrete && uiState.surroundFallback && shownChannels > 2
    val previewChannels = if (outputRefusedWide) 2 else shownChannels
    val previewStatus = if (outputRefusedWide) "Stereo only" else layoutName

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── Status ─────────────────────────────────────────────────────────────────────────────────────
        SurroundCard {
            // On/off switch. OFF stops the simulator route; "Connect to audio mode" is not turned on by it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Surround simulator",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = simulatorOn,
                    enabled = !uiState.isConnectingEngineRoute,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurroundAmber,
                        checkedTrackColor = SurroundAmber.copy(0.4f),
                        uncheckedThumbColor = SurroundAmber,
                        uncheckedTrackColor = SurroundAmber.copy(0.25f),
                        uncheckedBorderColor = SurroundAmber.copy(0.5f),
                        disabledCheckedThumbColor = SurroundAmber.copy(0.4f),
                        disabledUncheckedThumbColor = Color.Gray.copy(0.4f)
                    )
                )
            }

            // ── Layout: Stereo / 5.1 / 7.1 / Auto, right under the on/off switch ─────────────────────────
            // Always visible so a layout can be picked before the simulator is switched on. A real 5.1 / 7.1 soundtrack
            // keeps its own channels, so the chips are greyed out for it instead of silently doing nothing.
            Spacer(Modifier.height(12.dp))
            SurroundLabel("LAYOUT")
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.alpha(if (simulatorOn) 1f else 0.6f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SurroundLayout.values().forEach { option ->
                    SurroundChip(
                        label = option.displayName,
                        selected = layout == option,
                        enabled = !discrete,
                        onClick = { onLayout(option) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    discrete -> "This soundtrack is a real $layoutName mix and keeps its own channels, so the layout cannot change it."
                    !simulatorOn -> "Pick the layout now; it is used as soon as the simulator is on."
                    layout == SurroundLayout.AUTO -> "Auto follows the soundtrack: 5.1 stays 5.1, 7.1 stays 7.1, stereo stays stereo (now $layoutName)."
                    else -> "Applies to stereo soundtracks. A real 5.1 / 7.1 track keeps its own channels."
                },
                color = Color.White.copy(0.55f),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))

            if (!simulatorOn) {
                Text(
                    text = "OFF",
                    color = SurroundAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (uiState.routeAudioToEngine) {
                        "The simulator is off, so it is skipped in the audio track pipeline."
                    } else {
                        "The simulator is off, so the soundtrack plays in its own layout."
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "SURROUND SIMULATOR",
                    color = SurroundAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        discrete -> "Real $layoutName soundtrack (${uiState.surroundSourceChannels} channels). Original channels are kept, nothing is upmixed."
                        shownChannels > 2 -> "Stereo soundtrack upmixed to $layoutName."
                        else -> "Stereo output: nothing is upmixed."
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (!uiState.surroundActive && shownChannels > 2) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "The simulator is not active for this soundtrack right now " +
                            "(an output that refused multichannel keeps it out of the path).",
                        color = SurroundAmberSoft,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Phone speakers, headphones and Bluetooth play this folded down to stereo. " +
                        "HDMI, a USB DAC or an AV receiver give real discrete speakers.",
                    color = Color.White.copy(0.55f),
                    fontSize = 11.sp
                )
            }
        }

        uiState.engineRouteError?.let { err ->
            Text(text = err, color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        // Simulator off: nothing below applies (layout, mode, presets and levels only shape the simulator).
        if (!simulatorOn) return@Column

        // ── Speaker preview: speakers move closer / further as their level changes ────────────────────
        SurroundStageCard(
            channels = previewChannels,
            trimsDb = settings.trimsDb,
            isPlaying = uiState.isPlaying,
            statusText = previewStatus
        )

        // Nothing is upmixed on a stereo output, so mode / presets / levels have nothing to shape.
        if (shownChannels <= 2) return@Column

        // ── Mode (stereo upmix only) ──────────────────────────────────────────────────────────────────
        if (!discrete) {
            SurroundLabel("MODE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SurroundMode.values().forEach { mode ->
                    SurroundChip(
                        label = mode.displayName,
                        selected = settings.mode == mode,
                        onClick = { onMode(mode) }
                    )
                }
            }
        }

        // ── Presets ───────────────────────────────────────────────────────────────────────────────────
        SurroundLabel("PRESETS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BUILT_IN_SURROUND_PRESETS.forEach { preset ->
                SurroundChip(
                    label = preset.name,
                    selected = settings.activePreset == preset.name,
                    onClick = { onPreset(preset.name) }
                )
            }
            if (settings.customPresetSaved) {
                SurroundChip(
                    label = SURROUND_CUSTOM_PRESET_NAME,
                    selected = settings.activePreset == SURROUND_CUSTOM_PRESET_NAME,
                    onClick = { onPreset(SURROUND_CUSTOM_PRESET_NAME) }
                )
            }
        }

        // ── Channel levels ────────────────────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SurroundLabel("CHANNEL LEVELS")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Save as $SURROUND_CUSTOM_PRESET_NAME",
                    color = SurroundAmberSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSaveCustom).padding(4.dp)
                )
                Text(
                    "Reset",
                    color = SurroundAmberSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onResetTrims).padding(4.dp)
                )
            }
        }
        if (discrete) {
            Text(
                "Shown relative to the Default preset: 0 dB leaves the original mix untouched.",
                color = Color.White.copy(0.55f),
                fontSize = 11.sp
            )
        }

        SurroundChannel.values()
            .filter { sevenOne || !it.sevenOneOnly }
            .forEach { channel ->
                val index = channel.ordinal
                val offset = if (discrete) DEFAULT_SURROUND_TRIMS_DB[index] else 0f
                val trim = settings.trimsDb.getOrElse(index) { DEFAULT_SURROUND_TRIMS_DB[index] }
                SurroundLevelRow(
                    label = channel.label,
                    value = trim - offset,
                    range = (SURROUND_TRIM_MIN_DB - offset)..(SURROUND_TRIM_MAX_DB - offset),
                    onChange = { v -> onTrim(index, ((v * 2f).roundToInt() / 2f) + offset) }
                )
            }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SurroundCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.09f), Color.White.copy(0.04f))))
            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun SurroundLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun SurroundChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) SurroundAmber else Color.White.copy(0.08f))
            .border(1.dp, if (selected) SurroundAmberSoft else Color.White.copy(0.14f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun SurroundLevelRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = SurroundAmber,
                activeTrackColor = SurroundAmber,
                inactiveTrackColor = Color.White.copy(0.12f)
            )
        )
        Text(
            text = formatDb(value),
            color = if (value == 0f) Color.White.copy(0.55f) else SurroundAmberSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp)
        )
    }
}

private fun formatDb(db: Float): String {
    val rounded = (db * 2f).roundToInt() / 2f
    val sign = if (rounded > 0f) "+" else ""
    return "$sign${"%.1f".format(rounded)} dB"
}
