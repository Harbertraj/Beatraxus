package com.beatraxus.app.ui.components.dsp

import com.beatraxus.app.ui.theme.BgGradBottom
import com.beatraxus.app.ui.theme.BgGradTop
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.SURROUND_ALL_SLOT_NAMES
import com.beatraxus.app.model.SURROUND_DISTANCE_MAX_M
import com.beatraxus.app.model.SURROUND_DISTANCE_MIN_M
import com.beatraxus.app.model.SurroundChannel
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.sanitizeSurroundDistances
import com.beatraxus.app.model.surroundSlotFilled
import com.beatraxus.app.model.surroundSpeakerDelaysMs
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.ui.theme.Icon
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Studio DSP > Surround: headphone surround, live speaker meters, A/B hold, speaker setup (test noise, distances, bass
 * management), smart steering and the preset slot row. Each panel only edits [DspConfig] through the view model.
 */

private const val TEST_NOISE_SECONDS = 6

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
    inactiveTrackColor = Color.White.copy(0.12f)
)

// ── Live meters and A/B hold ─────────────────────────────────────────────────────────────────────────────────

/** One thin bar per speaker that rises and falls with the sound that really goes to it. */
@Composable
internal fun SurroundMeterStrip(
    viewModel: PlayerViewModel,
    running: Boolean,
    channels: Int,
    accent: Color
) {
    val levels = remember { FloatArray(8) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) {
            levels.fill(0f)
            tick.longValue++
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            if (!viewModel.readSurroundLevels(levels)) levels.fill(0f)
            tick.longValue++
        }
    }
    Column(modifier = Modifier.extrasCard(accent), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardTitle("LIVE LEVELS", Icons.Rounded.GraphicEq, accent)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SurroundChannel.entries.forEachIndexed { i, channel ->
                val present = channels >= 8 || (channels >= 6 && !channel.sevenOneOnly) ||
                    (channels < 6 && (channel == SurroundChannel.L || channel == SurroundChannel.R))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        val t = tick.longValue // read here so only this drawing is redone every frame
                        if (t < 0L) return@Canvas
                        val radius = CornerRadius(4.dp.toPx())
                        drawRoundRect(Color.White.copy(0.06f), Offset.Zero, size, radius)
                        val v = if (present) levels[i].coerceIn(0f, 1f) else 0f
                        // 54 dB of range: a quiet speaker still shows a sliver, a loud one fills the bar.
                        val db = 20f * log10(max(v, 1e-4f))
                        val frac = ((db + 54f) / 54f).coerceIn(0f, 1f)
                        if (frac > 0.01f) {
                            val h = size.height * frac
                            drawRoundRect(
                                accent.copy(if (present) 0.95f else 0.2f),
                                Offset(0f, size.height - h),
                                Size(size.width, h),
                                radius
                            )
                        }
                    }
                    Text(
                        channel.label,
                        color = if (present) Color.White else Color.White.copy(0.3f),
                        fontSize = 10.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

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
            colors = accentSliderColors(accent)
        )
    }
}

// ── Speaker setup: test noise, distances, bass management ────────────────────────────────────────────────────

@Composable
internal fun SpeakerSetupPanel(
    config: DspConfig,
    accent: Color,
    sevenOne: Boolean,
    embedded: Boolean = false,
    onTest: (Int) -> Unit,
    onCalibration: (Boolean) -> Unit,
    onDistance: (Int, Float) -> Unit,
    onResetDistances: () -> Unit,
    onBassManagement: (Boolean) -> Unit
) {
    val distances = sanitizeSurroundDistances(config.surroundSpeakerDistancesM)
    val delays = surroundSpeakerDelaysMs(distances, sevenOne)
    // The test noise stops by itself, and when the panel leaves the screen, so it can never be left playing.
    LaunchedEffect(config.surroundTestChannel) {
        if (config.surroundTestChannel >= 0) {
            kotlinx.coroutines.delay(TEST_NOISE_SECONDS * 1000L)
            onTest(-1)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onTest(-1) } }
    Column(
        modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.extrasCard(accent),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CardTitle("SPEAKER SETUP", Icons.Rounded.SurroundSound, accent) {
            ResetLink({ onResetDistances() }, accent)
        }
        Hint("Tap a speaker to play test noise on it for 6 seconds, everything else is muted. Tap it again to stop.")
        SurroundChannel.entries.chunked(4).forEach { rowChannels ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowChannels.forEach { channel ->
                    val enabled = sevenOne || !channel.sevenOneOnly
                    PresetChip(
                        name = channel.label,
                        selected = config.surroundTestChannel == channel.ordinal,
                        enabled = enabled,
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    ) { onTest(if (config.surroundTestChannel == channel.ordinal) -1 else channel.ordinal) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DISTANCE DELAY", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp, modifier = Modifier.weight(1f)
            )
            PremiumSwitch(checked = config.surroundCalibrationEnabled, onCheckedChange = onCalibration, accentColor = accent)
        }
        Hint("Set how far each speaker is from where you sit. Nearer speakers are delayed so every sound reaches you together.")
        SurroundChannel.entries.forEachIndexed { i, channel ->
            if (!sevenOne && channel.sevenOneOnly) return@forEachIndexed
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.fullName.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp, modifier = Modifier.weight(1f)
                )
                Text(
                    String.format(java.util.Locale.US, "%.1f m", distances[i]) +
                        if (config.surroundCalibrationEnabled && delays[i] >= 0.05f) {
                            String.format(java.util.Locale.US, "  +%.1f ms", delays[i])
                        } else "",
                    color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black
                )
            }
            Slider(
                value = distances[i],
                onValueChange = { onDistance(i, it) },
                valueRange = SURROUND_DISTANCE_MIN_M..SURROUND_DISTANCE_MAX_M,
                colors = accentSliderColors(accent)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BASS MANAGEMENT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp, modifier = Modifier.weight(1f)
            )
            PremiumSwitch(checked = config.surroundBassManagement, onCheckedChange = onBassManagement, accentColor = accent)
        }
        Hint(
            "For small speakers: the main speakers stop below the bass cutoff (Upmix Lab > Bass cutoff, above) and the " +
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
