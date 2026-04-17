package com.beatflowy.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatflowy.app.ui.theme.*

@Composable
fun AudioInfoChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accentColor: Color = AccentBlue,
    highlighted: Boolean = false
) {
    val borderColor by animateColorAsState(
        targetValue    = if (highlighted) accentColor else Divider,
        animationSpec  = tween(400),
        label          = "chipBorder"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgElevated)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, color = TextMuted)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp, color = if (highlighted) accentColor else TextPrimary)
        }
    }
}

@Composable
fun AudioInfoBar(
    inputSampleRate: Int,
    outputSampleRate: Int,
    outputDevice: String,
    resamplingEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val isResampled = resamplingEnabled && inputSampleRate != outputSampleRate
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AudioInfoChip("IN",  formatHz(inputSampleRate),  accentColor = AccentBlueSoft,
            highlighted = false, modifier = Modifier.weight(1f))
        Text(if (isResampled) "→" else "·",
            color = if (isResampled) AccentBlue else TextMuted,
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
        AudioInfoChip("OUT", formatHz(outputSampleRate), accentColor = AccentBlue,
            highlighted = isResampled, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        AudioInfoChip("DEVICE", outputDevice, accentColor = AccentRedSoft,
            highlighted = false, modifier = Modifier.weight(1.4f))
    }
}

private fun formatHz(hz: Int): String {
    if (hz <= 0) return "—"
    val khz = hz / 1000f
    return if (khz == khz.toLong().toFloat()) "${khz.toInt()}kHz" else "${khz}kHz"
}
