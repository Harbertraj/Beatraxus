package com.beatraxus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.ui.theme.DspPalette
import com.beatraxus.app.viewmodel.PlayerViewModel

/**
 * Settings > Appearance > Dual Colour Palette  ("Studio DSP Interface").
 * Pick a ready-made dual palette (default: Yellow + Orange) or build a custom dual colour pair.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DspPaletteContent(uiState: PlayerUiState, playerViewModel: PlayerViewModel) {
    val appearance = uiState.appearance
    val isCustom = appearance.dspPaletteId == DspPalette.CUSTOM_ID

    var customPrimary by remember(appearance.dspCustomPrimary) { mutableStateOf(Color(appearance.dspCustomPrimary)) }
    var customSecondary by remember(appearance.dspCustomSecondary) { mutableStateOf(Color(appearance.dspCustomSecondary)) }

    val activePalette = if (isCustom) DspPalette.custom(customPrimary, customSecondary)
    else DspPalette.resolve(appearance.dspPaletteId, appearance.dspCustomPrimary, appearance.dspCustomSecondary)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Studio DSP Interface",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = "Choose the two colours that light up the Studio DSP screen: the Spatial Audio section, EQ curve, knobs, sheets and glow.",
            color = SettingSubtitleColor,   // same as the main settings subtitles
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        DspPalettePreview(activePalette)

        SectionLabel("PRESETS")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            DspPalette.presets.forEach { palette ->
                PaletteChip(
                    palette = palette,
                    selected = appearance.dspPaletteId == palette.id,
                    onClick = { playerViewModel.setDspPalette(palette.id) }
                )
            }
            PaletteChip(
                palette = DspPalette.custom(customPrimary, customSecondary),
                selected = isCustom,
                onClick = {
                    playerViewModel.setDspCustomColors(customPrimary.toArgb(), customSecondary.toArgb())
                }
            )
        }

        SectionLabel("CUSTOM DUAL COLORS")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(0.18f))
                .border(0.8.dp, Color.White.copy(0.10f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ColorEditor(
                label = "Primary colour",
                color = customPrimary,
                onColorChange = { customPrimary = it },
                onCommit = { playerViewModel.setDspCustomColors(customPrimary.toArgb(), customSecondary.toArgb()) }
            )
            ColorEditor(
                label = "Secondary colour",
                color = customSecondary,
                onColorChange = { customSecondary = it },
                onCommit = { playerViewModel.setDspCustomColors(customPrimary.toArgb(), customSecondary.toArgb()) }
            )
            Text(
                text = "Changing a custom colour switches the Studio DSP interface to Custom Dual automatically.",
                color = SettingSubtitleColor,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

    }
}

/**
 * Settings > Appearance > Surround Screen. Everything here is read live by the Studio DSP > Surround page
 * ([com.beatraxus.app.ui.components.dsp.PremiumSurroundCard]); the preview below shows the result immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurroundAppearanceContent(uiState: PlayerUiState, vm: PlayerViewModel) {
    val appearance = uiState.appearance
    val palette = DspPalette.from(appearance)
    val accent = if (appearance.surroundColorGlow != 0) Color(appearance.surroundColorGlow) else palette.primary

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Surround Screen",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = "Change how Studio DSP > Surround looks. Changes show in the preview and on the Surround page straight away.",
            color = SettingSubtitleColor,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        // Live preview (all eight speakers on)
        com.beatraxus.app.ui.components.dsp.SurroundStagePreview(appearance)

        SectionLabel("STAGE")
        SettingsCard {
            Text("Room theme", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(
                options = listOf("dark" to "Dark", "studio" to "Studio", "living" to "Living Room"),
                selected = appearance.surroundRoomTheme,
                accent = accent,
                onSelect = vm::setSurroundRoomTheme
            )
            ToggleRow("Speaker icons", "Draw speaker cabinets with a driver instead of plain dots", appearance.surroundShowSpeakerIcons, accent, vm::setSurroundShowSpeakerIcons)
            ToggleRow("Labels", "L, C, R, SL, SR, BL, BR, SW under each speaker", appearance.surroundShowLabels, accent, vm::setSurroundShowLabels)
            ToggleRow("Sound cones", "Soft cone of sound from each active speaker to you", appearance.surroundShowBeams, accent, vm::setSurroundShowBeams)
            ToggleRow("Sound arcs", "Arcs that radiate from active speakers and move with the music", appearance.surroundShowArcs, accent, vm::setSurroundShowArcs)
            ToggleRow("Screen and listener", "Screen bar at the front and the sweet-spot halo around you", appearance.surroundShowScreen, accent, vm::setSurroundShowScreen)
            ToggleRow("Animations", "Moving arcs, halo and screen glow. Off saves battery", appearance.surroundAnimated, accent, vm::setSurroundAnimated)
        }

        SectionLabel("VISUALIZER")
        SettingsCard {
            ToggleRow("Visualizer", "Loudness graphic under the listener", appearance.surroundVisualizerEnabled, accent, vm::setSurroundVisualizerEnabled)
            if (appearance.surroundVisualizerEnabled) {
                ChoiceRow(
                    options = listOf("wave" to "Wave", "spectrum" to "Spectrum", "circle" to "Circle"),
                    selected = appearance.surroundVisualizerStyle,
                    accent = accent,
                    onSelect = vm::setSurroundVisualizerStyle
                )
            }
        }

        SectionLabel("COLOURS")
        SettingsCard {
            SpeakerColorRow("Accent / room glow", "glow", appearance.surroundColorGlow, palette.primary, vm)
            SpeakerColorRow("Front speakers", "front", appearance.surroundColorFront, palette.primary, vm)
            SpeakerColorRow("Surround speakers", "surround", appearance.surroundColorSurround, palette.secondary, vm)
            SpeakerColorRow("Rear speakers", "rear", appearance.surroundColorRear, palette.tertiary, vm)
            SpeakerColorRow("Subwoofer", "sub", appearance.surroundColorSub, palette.secondary, vm)
            Text(
                text = "Auto follows the Studio DSP dual colour palette. The accent colour also tints the switch, buttons and panels on the Surround page.",
                color = SettingSubtitleColor,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(0.05f))
                .border(1.dp, Color.White.copy(0.12f), RoundedCornerShape(16.dp))
                .clickable { vm.resetSurroundAppearance() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Reset to default look", color = Color.White.copy(0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(0.18f))
            .border(0.8.dp, Color.White.copy(0.10f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = SettingSubtitleColor, fontSize = 11.sp, lineHeight = 15.sp)
        }
        PremiumSwitch(checked = checked, onCheckedChange = onChange, accentColor = accent)
    }
}

private val SurroundSwatches = listOf(
    0xFF22D3EE, 0xFF8B5CF6, 0xFFFF7A00, 0xFF3B82F6, 0xFF3CF2C0, 0xFFFF6B9A, 0xFFFFD60A
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerColorRow(label: String, group: String, current: Int, paletteColor: Color, vm: PlayerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 0 = follow the palette
            Swatch(paletteColor, selected = current == 0, label = "Auto") { vm.setSurroundColor(group, 0) }
            SurroundSwatches.forEach { argb ->
                Swatch(Color(argb), selected = current == argb, label = null) { vm.setSurroundColor(group, argb) }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) Color.White else Color.White.copy(0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(label, color = Color.Black.copy(0.75f), fontSize = 8.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, accent: Color, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (id, name) ->
            val isSel = id == selected
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isSel) accent.copy(0.16f) else Color.White.copy(0.05f))
                    .border(if (isSel) 1.5.dp else 1.dp, if (isSel) accent else Color.White.copy(0.12f), shape)
                    .clickable { onSelect(id) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(name, color = Color.White.copy(if (isSel) 1f else 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(0.45f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Miniature of the DSP Spatial Audio section painted with the given palette. */
@Composable
private fun DspPalettePreview(palette: DspPalette) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))
            .border(
                1.dp,
                Brush.linearGradient(listOf(palette.primary.copy(0.5f), Color.White.copy(0.06f), palette.secondary.copy(0.4f))),
                shape
            )
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "STUDIO DSP",
            color = palette.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.ink.copy(0.7f))
                .border(1.dp, Color.White.copy(0.10f), RoundedCornerShape(50))
                .padding(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(palette.primary, palette.secondary)))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) { Text("CLASSIC", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("MODERN", color = Color.White.copy(0.5f), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
        // Fake EQ bars
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val heights = listOf(0.35f, 0.55f, 0.8f, 0.6f, 1f, 0.7f, 0.45f, 0.65f, 0.9f, 0.5f, 0.3f, 0.55f)
            heights.forEach { h ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((46 * h).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.verticalGradient(listOf(palette.primary, palette.secondary)))
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(palette.primary))
            Text(palette.name, color = palette.accentSoft, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(10.dp).clip(CircleShape).background(palette.secondary))
        }
    }
}

@Composable
private fun PaletteChip(palette: DspPalette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) palette.primary.copy(0.14f) else Color.Black.copy(0.18f))
            .border(
                if (selected) 1.5.dp else 0.8.dp,
                if (selected) palette.primary else Color.White.copy(0.12f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Overlapping dual swatch
        Box(Modifier.width(34.dp).height(24.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(palette.primary))
            Box(
                Modifier
                    .offset(x = 10.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(palette.secondary)
                    .border(1.5.dp, Color(0xFF121212), CircleShape)
            )
        }
        Text(
            text = palette.name,
            color = Color.White.copy(if (selected) 1f else 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.primary, modifier = Modifier.size(16.dp))
        }
    }
}

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    return hsv
}

private fun hsvToColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

private fun Color.toHexString(): String = "%06X".format(this.toArgb() and 0xFFFFFF)

@Composable
private fun ColorEditor(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit,
    onCommit: () -> Unit
) {
    // HSV is the source of truth while editing so hue/saturation are not lost on grey colours.
    var hsv by remember { mutableStateOf(color.toHsv()) }
    var hex by remember { mutableStateOf(color.toHexString()) }

    fun update(h: Float, s: Float, v: Float) {
        hsv = floatArrayOf(h, s, v)
        val c = hsvToColor(h, s, v)
        hex = c.toHexString()
        onColorChange(c)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.5.dp, Color.White.copy(0.3f), CircleShape)
            )
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = hex,
                onValueChange = { raw ->
                    val clean = raw.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    hex = clean
                    if (clean.length == 6) {
                        val parsed = Color(0xFF000000.toInt() or clean.toInt(16))
                        hsv = parsed.toHsv()
                        onColorChange(parsed)
                        onCommit()
                    }
                },
                prefix = { Text("#", color = Color.White.copy(0.5f)) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.width(140.dp)
            )
        }

        GradientSlider(
            title = "Hue",
            value = hsv[0] / 360f,
            brush = Brush.horizontalGradient(
                listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { hsvToColor(it, 1f, 1f) }
            ),
            onChange = { update(it * 360f, hsv[1], hsv[2]) },
            onFinished = onCommit
        )
        GradientSlider(
            title = "Saturation",
            value = hsv[1],
            brush = Brush.horizontalGradient(listOf(hsvToColor(hsv[0], 0f, hsv[2]), hsvToColor(hsv[0], 1f, hsv[2]))),
            onChange = { update(hsv[0], it, hsv[2]) },
            onFinished = onCommit
        )
        GradientSlider(
            title = "Brightness",
            value = hsv[2],
            brush = Brush.horizontalGradient(listOf(Color.Black, hsvToColor(hsv[0], hsv[1], 1f))),
            onChange = { update(hsv[0], hsv[1], it) },
            onFinished = onCommit
        )
    }
}

@Composable
private fun GradientSlider(
    title: String,
    value: Float,
    brush: Brush,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White.copy(0.55f), fontSize = 11.sp, modifier = Modifier.width(78.dp))
        Box(modifier = Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(brush)
                    .border(0.8.dp, Color.White.copy(0.18f), RoundedCornerShape(50))
            )
            Slider(
                value = value.coerceIn(0f, 1f),
                onValueChange = onChange,
                onValueChangeFinished = onFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
