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
            color = Color.White.copy(0.5f),
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
                color = Color.White.copy(0.4f),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
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
            text = "SPATIAL AUDIO",
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
