package com.beatraxus.app.ui.components.dsp

import com.beatraxus.app.ui.theme.BgGradBottom
import com.beatraxus.app.ui.theme.BgGradTop
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.SURROUND_ALL_SLOT_NAMES
import com.beatraxus.app.model.surroundSlotFilled
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.ui.theme.Icon
import kotlin.math.roundToInt

/**
 * Studio DSP > Surround: headphone surround, live A/B hold, bass management, smart steering and the preset slot row. Each panel only edits [DspConfig] through the view model.
 */

private fun Modifier.extrasCard(accent: Color): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(16.dp))
    .background(Brush.verticalGradient(listOf(BgGradTop, BgGradBottom)))
    .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
    .padding(12.dp)

@Composable
private fun CardTitle(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    trailing: @Composable () -> Unit = {}
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp)
}

@Composable
private fun ResetLink(onClick: () -> Unit, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(4.dp)
    ) {
        Icon(Icons.Rounded.RestartAlt, null, tint = accent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text("RESET", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun accentSliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent,
    inactiveTrackColor = Color.White.copy(0.12f),
    disabledThumbColor = accent.copy(0.35f),
    disabledActiveTrackColor = accent.copy(0.25f),
    disabledInactiveTrackColor = Color.White.copy(0.08f)
)

// ── A/B hold ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Press and hold: plain stereo as recorded; let go: the surround mix again. For comparing what the upmix does. */
@Composable
internal fun HoldOriginalButton(accent: Color, enabled: Boolean, onHold: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (down) accent.copy(0.30f) else Color.White.copy(0.06f))
            .border(1.dp, if (down) accent else Color.White.copy(0.14f), RoundedCornerShape(14.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    down = true
                    onHold(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        down = false
                        onHold(false)
                    }
                })
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.SurroundSound, null, tint = if (enabled) accent else Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (down) "ORIGINAL STEREO" else "HOLD TO HEAR ORIGINAL",
            color = if (enabled) Color.White else Color.White.copy(0.35f),
            fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp
        )
    }
}

// ── Smart steering ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SmartSteeringPanel(
    config: DspConfig,
    accent: Color,
    onEnabled: (Boolean) -> Unit,
    onAmount: (Float) -> Unit
) {
    Column(modifier = Modifier.extrasCard(accent), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle("SMART STEERING", Icons.Rounded.Tune, accent) {
            PremiumSwitch(checked = config.surroundSmartSteering, onCheckedChange = onEnabled, accentColor = accent)
        }
        Hint(
            "The upmix listens to the song as it plays. Voices and instruments panned to the middle are pulled to the " +
                "centre speaker; wide, loose sound (room, reverb, crowd) goes to the surrounds. It works on how alike " +
                "left and right are, so it is a steering aid, not a full source separation."
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "STRENGTH", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp, modifier = Modifier.weight(1f)
            )
            Text("${(config.surroundSmartSteeringAmount * 100f).roundToInt()}%", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Slider(
            value = config.surroundSmartSteeringAmount,
            onValueChange = onAmount,
            valueRange = 0f..1f,
            enabled = LocalSurroundControlsEnabled.current,
            colors = accentSliderColors(accent)
        )
    }
}

// ── Bass management ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * Bass management switch (what is left of the old Speaker setup panel: the distances now live in the room view and
 * the level trims). [embedded] = drawn inside another card, without a card of its own.
 */
@Composable
internal fun BassManagementPanel(
    config: DspConfig,
    accent: Color,
    embedded: Boolean = false,
    onBassManagement: (Boolean) -> Unit
) {
    Column(
        modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.extrasCard(accent),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (embedded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "BASS MANAGEMENT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp, modifier = Modifier.weight(1f)
                )
                PremiumSwitch(checked = config.surroundBassManagement, onCheckedChange = onBassManagement, accentColor = accent)
            }
        } else {
            CardTitle("BASS MANAGEMENT", Icons.Rounded.SurroundSound, accent) {
                PremiumSwitch(checked = config.surroundBassManagement, onCheckedChange = onBassManagement, accentColor = accent)
            }
        }
        Hint(
            "For small speakers: the main speakers stop below the bass cutoff (Upmix Lab > Bass cutoff) and the " +
                "subwoofer plays the low end. Leave it off if your speakers are full range."
        )
    }
}

// ── Preset slots, copy / paste code ──────────────────────────────────────────────────────────────────────────

@Composable
internal fun PresetSlotsRow(
    config: DspConfig,
    accent: Color,
    onSave: (String) -> Unit,
    exportCode: () -> String,
    onImport: (String?) -> Boolean
) {
    val context = LocalContext.current
    Text(
        "SAVE CURRENT MIX TO", color = Color.White.copy(0.55f), fontSize = 9.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SURROUND_ALL_SLOT_NAMES.forEach { name ->
            PresetChip(
                name = name.removePrefix("Custom "),
                selected = config.surroundActivePreset == name && config.surroundSlotFilled(name),
                enabled = true,
                accent = accent,
                modifier = Modifier.weight(1f)
            ) { onSave(name) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        LayoutButton("Copy code", Icons.Rounded.Save, false, accent, Modifier.weight(1f)) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Beatraxus surround mix", exportCode()))
            Toast.makeText(context, "Mix code copied", Toast.LENGTH_SHORT).show()
        }
        LayoutButton("Paste code", Icons.Rounded.Tune, false, accent, Modifier.weight(1f)) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            val ok = onImport(text)
            Toast.makeText(context, if (ok) "Mix applied" else "No Beatraxus mix code on the clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
