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
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import kotlin.math.roundToInt

private val SurroundAmber = Color(0xFFFF8F00)
private val SurroundAmberSoft = Color(0xFFFFB347)

/**
 * Surround simulator controls inside the video player. Same settings as Studio DSP > 5.1 / 7.1 (they are shared),
 * shown only while the simulator is really in this video's signal path.
 *
 * A soundtrack that already is a real 5.1 / 7.1 mix keeps its discrete channels: layout and mode do nothing for it
 * (they only shape the stereo upmix), so they are hidden, and the levels are shown relative to the Default preset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoSurroundSection(
    uiState: VideoPlayerUiState,
    onLayout: (SurroundLayout) -> Unit,
    onMode: (SurroundMode) -> Unit,
    onPreset: (String) -> Unit,
    onTrim: (Int, Float) -> Unit,
    onResetTrims: () -> Unit,
    onSaveCustom: () -> Unit
) {
    val settings = uiState.surroundSettings
    val discrete = uiState.surroundDiscrete
    val sevenOne = if (discrete) uiState.surroundSourceChannels == 8 else uiState.surroundOutputChannels == 8
    val layoutName = if (sevenOne) "7.1" else "5.1"
    // The 5.1 engine never runs plain Stereo (it uses 5.1 instead), so show that as the selected layout.
    val shownLayout = if (settings.layout == SurroundLayout.STEREO) SurroundLayout.FIVE_ONE else settings.layout

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── Status ─────────────────────────────────────────────────────────────────────────────────────
        SurroundCard {
            Text(
                text = if (uiState.surroundEngineRoute) "5.1 ENGINE" else "AUDIO MODE · DSP STUDIO",
                color = SurroundAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (discrete) {
                    "Real $layoutName soundtrack (${uiState.surroundSourceChannels} channels). Original channels are kept, nothing is upmixed."
                } else {
                    "Stereo soundtrack upmixed to $layoutName."
                },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Phone speakers, headphones and Bluetooth play this folded down to stereo. " +
                    "HDMI, a USB DAC or an AV receiver give real discrete speakers.",
                color = Color.White.copy(0.55f),
                fontSize = 11.sp
            )
        }

        // ── Layout + mode (stereo upmix only) ─────────────────────────────────────────────────────────
        if (!discrete) {
            SurroundLabel("LAYOUT")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SurroundLayout.FIVE_ONE, SurroundLayout.SEVEN_ONE, SurroundLayout.AUTO).forEach { layout ->
                    SurroundChip(
                        label = layout.displayName,
                        selected = shownLayout == layout,
                        onClick = { onLayout(layout) }
                    )
                }
            }

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
