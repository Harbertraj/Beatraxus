package com.beatraxus.app.ui.components.dsp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.BUILT_IN_SURROUND_PRESETS
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME
import com.beatraxus.app.model.SURROUND_TRIM_MAX_DB
import com.beatraxus.app.model.SURROUND_TRIM_MIN_DB
import com.beatraxus.app.model.SurroundChannel
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.SurroundMode
import com.beatraxus.app.model.sanitizeSurroundTrims
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.ui.theme.DspPaletteState
import com.beatraxus.app.ui.utils.LocalLiveAudioLevel
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Studio DSP > 5.1/7.1 page. Everything here only edits [com.beatraxus.app.model.DspConfig]; the audio side lives
 * in AudioEngine / AudioDspPipeline / SurroundUpmixer.
 */
@Composable
fun PremiumSurroundCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val look = uiState.appearance
    val pal = DspPaletteState.current

    val forcedOff = config.bypassAll || config.bitPerfectEnabled
    val fallback = uiState.dsp.surroundFallbackActive
    val liveChannels = uiState.dsp.surroundOutputChannels

    // Channels the room view shows as "playing": what the output really carries once audio flows, otherwise what
    // the chosen layout will produce.
    val plannedChannels = when {
        !config.surroundEnabled || forcedOff || fallback -> 2
        liveChannels > 2 -> liveChannels
        else -> when (config.surroundLayout) {
            SurroundLayout.SEVEN_ONE -> 8
            SurroundLayout.FIVE_ONE, SurroundLayout.AUTO -> 6
            SurroundLayout.STEREO -> 2
        }
    }
    val trims = sanitizeSurroundTrims(config.surroundChannelTrimsDb)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(pal.primary, pal.secondary, 22.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.SurroundSound, null, tint = pal.primary, modifier = Modifier.size(26.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "5.1 / 7.1 SURROUND SIMULATOR",
                    color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.2.sp
                )
                Text("Immersive sound. Your way.", color = Color.White.copy(0.55f), fontSize = 10.sp)
            }
            val (statusText, statusColor) = when {
                forcedOff && config.surroundEnabled -> "BYPASSED" to Color(0xFFFFB74D)
                fallback -> "STEREO ONLY" to Color(0xFFFFB74D)
                plannedChannels == 8 -> "7.1 ACTIVE" to pal.primary
                plannedChannels == 6 -> "5.1 ACTIVE" to pal.primary
                else -> "OFF" to Color.White.copy(0.4f)
            }
            Text(
                statusText,
                color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, statusColor.copy(0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        // ── Room view ─────────────────────────────────────────────────────────────────────────────────────
        SurroundRoomView(plannedChannels, trims, look)

        // ── Layout buttons: Stereo / 5.1 / 7.1 / Auto ─────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LayoutButton("Stereo", Icons.Rounded.Headphones, config.surroundLayout == SurroundLayout.STEREO, Modifier.weight(1f)) {
                viewModel.setSurroundLayout(SurroundLayout.STEREO)
            }
            LayoutButton("5.1", Icons.Rounded.SurroundSound, config.surroundLayout == SurroundLayout.FIVE_ONE, Modifier.weight(1f)) {
                viewModel.setSurroundLayout(SurroundLayout.FIVE_ONE)
            }
            LayoutButton("7.1", Icons.Rounded.SurroundSound, config.surroundLayout == SurroundLayout.SEVEN_ONE, Modifier.weight(1f)) {
                viewModel.setSurroundLayout(SurroundLayout.SEVEN_ONE)
            }
            LayoutButton("Auto", Icons.Rounded.AutoMode, config.surroundLayout == SurroundLayout.AUTO, Modifier.weight(1f)) {
                viewModel.setSurroundLayout(SurroundLayout.AUTO)
            }
        }

        // Honest status line: tells the user why the sound may still be stereo.
        val note = when {
            config.surroundEnabled && config.bitPerfectEnabled -> "Bit-Perfect is on, so the output stays untouched. Turn it off to use surround."
            config.surroundEnabled && config.bypassAll -> "A/B bypass is on, so surround is bypassed."
            fallback -> "This output device only accepts stereo, so playback stays in stereo."
            config.surroundEnabled && config.surroundLayout == SurroundLayout.STEREO -> "Layout is Stereo, so nothing is upmixed. Pick 5.1, 7.1 or Auto."
            config.surroundEnabled && plannedChannels > 2 && !uiState.isPlaying -> "Ready. Surround starts with the next play."
            !config.surroundEnabled -> "Surround is off. Audio plays exactly as before."
            else -> null
        }
        if (note != null) {
            Text(note, color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp)
        }

        // ── Channel levels ────────────────────────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(0.22f))
                .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = pal.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "CHANNEL LEVELS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.resetSurroundTrims() }.padding(4.dp)
                ) {
                    Icon(Icons.Rounded.RestartAlt, null, tint = pal.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("RESET", color = pal.primary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SurroundChannel.entries.forEachIndexed { index, channel ->
                    val active = config.surroundEnabled && !forcedOff && !fallback &&
                            plannedChannels > 2 && (!channel.sevenOneOnly || plannedChannels == 8)
                    TrimColumn(
                        label = channel.label,
                        valueDb = trims[index],
                        active = active,
                        color = channelColor(channel, look),
                        onChange = { viewModel.setSurroundChannelTrim(index, it) }
                    )
                }
            }
        }

        // ── Surround mode ─────────────────────────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(0.22f))
                .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = pal.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "SURROUND MODE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                PremiumSwitch(
                    checked = config.surroundEnabled,
                    onCheckedChange = { viewModel.setSurroundEnabled(it) },
                    accentColor = pal.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ModeChip("Movie", Icons.Rounded.Movie, config.surroundMode == SurroundMode.MOVIE, Modifier.weight(1f)) {
                    viewModel.setSurroundMode(SurroundMode.MOVIE)
                }
                ModeChip("Music", Icons.Rounded.MusicNote, config.surroundMode == SurroundMode.MUSIC, Modifier.weight(1f)) {
                    viewModel.setSurroundMode(SurroundMode.MUSIC)
                }
                ModeChip("Game", Icons.Rounded.SportsEsports, config.surroundMode == SurroundMode.GAME, Modifier.weight(1f)) {
                    viewModel.setSurroundMode(SurroundMode.GAME)
                }
                ModeChip("Custom", Icons.Rounded.Tune, config.surroundMode == SurroundMode.CUSTOM, Modifier.weight(1f)) {
                    viewModel.setSurroundMode(SurroundMode.CUSTOM)
                }
            }
        }

        // ── Presets ───────────────────────────────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(0.22f))
                .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PRESETS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.saveSurroundCustomPreset() }.padding(4.dp)
                ) {
                    Icon(Icons.Rounded.Save, null, tint = pal.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("SAVE AS CUSTOM 1", color = pal.primary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
            val names = BUILT_IN_SURROUND_PRESETS.map { it.name } + SURROUND_CUSTOM_PRESET_NAME
            names.chunked(4).forEach { rowNames ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowNames.forEach { name ->
                        val isCustomSlot = name == SURROUND_CUSTOM_PRESET_NAME
                        val available = !isCustomSlot || config.surroundCustomTrimsDb != null
                        PresetChip(
                            name = name,
                            selected = config.surroundActivePreset == name,
                            enabled = available,
                            modifier = Modifier.weight(1f)
                        ) { viewModel.applySurroundPreset(name) }
                    }
                    repeat(4 - rowNames.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ── Room view ─────────────────────────────────────────────────────────────────────────────────────────────

private class SpeakerSpot(val channel: SurroundChannel, val u: Float, val v: Float)

/** Positions on the floor: u = left..right, v = far wall (0) .. listener side (1). */
private val SPEAKER_SPOTS = listOf(
    SpeakerSpot(SurroundChannel.L, 0.30f, 0.14f),
    SpeakerSpot(SurroundChannel.R, 0.70f, 0.14f),
    SpeakerSpot(SurroundChannel.C, 0.50f, 0.08f),
    SpeakerSpot(SurroundChannel.SW, 0.88f, 0.10f),
    SpeakerSpot(SurroundChannel.SL, 0.07f, 0.52f),
    SpeakerSpot(SurroundChannel.SR, 0.93f, 0.52f),
    SpeakerSpot(SurroundChannel.BL, 0.22f, 0.88f),
    SpeakerSpot(SurroundChannel.BR, 0.78f, 0.88f)
)

private fun channelColor(channel: SurroundChannel, look: AppearanceConfig): Color {
    val pal = DspPaletteState.current
    fun pick(override: Int, fallback: Color) = if (override != 0) Color(override) else fallback
    return when (channel) {
        SurroundChannel.L, SurroundChannel.R, SurroundChannel.C -> pick(look.surroundColorFront, pal.primary)
        SurroundChannel.SL, SurroundChannel.SR -> pick(look.surroundColorSurround, pal.secondary)
        SurroundChannel.BL, SurroundChannel.BR -> pick(look.surroundColorRear, pal.tertiary)
        SurroundChannel.SW -> pick(look.surroundColorSub, lerp(pal.secondary, Color.White, 0.15f))
    }
}

@Composable
private fun SurroundRoomView(channels: Int, trimsDb: List<Float>, look: AppearanceConfig) {
    val pal = DspPaletteState.current
    val glow = if (look.surroundColorGlow != 0) Color(look.surroundColorGlow) else pal.primary

    val floorTop: Color
    val floorBottom: Color
    val wall: Color
    when (look.surroundRoomTheme) {
        "studio" -> { floorTop = Color(0xFF2B3340); floorBottom = Color(0xFF151A22); wall = Color(0xFF3A4556) }
        "living" -> { floorTop = Color(0xFF4A3322); floorBottom = Color(0xFF22160E); wall = Color(0xFF6B4A32) }
        else -> { floorTop = lerp(Color.Black, pal.secondary, 0.22f); floorBottom = lerp(Color.Black, pal.primary, 0.07f); wall = lerp(Color.Black, pal.primary, 0.20f) }
    }

    // Frame clock: only the draw phase reads it, so the page does not recompose every frame.
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame = it }
        }
    }
    val levelProvider = LocalLiveAudioLevel.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.18f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(0.35f))
            .border(1.dp, glow.copy(0.28f), RoundedCornerShape(18.dp))
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()

        // Room-space (u,v) -> pixel, with a simple perspective: the far wall is narrower than the listener side.
        fun px(u: Float, v: Float): Offset {
            val scale = 0.62f + 0.38f * v
            return Offset(wPx * (0.5f + (u - 0.5f) * scale), hPx * (0.14f + 0.80f * v))
        }

        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.18f)) {
            val t = frame / 1_000_000_000f
            val live = levelProvider?.invoke()
            val level = (live ?: (0.35f + 0.25f * sin(t * 2.2f))).coerceIn(0f, 1f)

            // Back wall + floor
            drawRect(Brush.verticalGradient(listOf(wall.copy(0.9f), wall.copy(0.35f)), 0f, hPx * 0.2f), size = Size(wPx, hPx * 0.2f))
            val floor = Path().apply {
                val a = px(0f, 0f); val b = px(1f, 0f); val c = px(1f, 1f); val d = px(0f, 1f)
                moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
            }
            drawPath(floor, Brush.verticalGradient(listOf(floorTop, floorBottom), hPx * 0.14f, hPx * 0.94f))
            drawPath(floor, glow.copy(0.35f), style = Stroke(1.2f))
            // Floor grid
            for (i in 1..5) {
                val v = i / 6f
                drawLine(glow.copy(0.10f), px(0f, v), px(1f, v), 1f)
            }
            for (i in 1..6) {
                val u = i / 7f
                drawLine(glow.copy(0.10f), px(u, 0f), px(u, 1f), 1f)
            }

            // Listener: soft glow + armchair
            val me = px(0.5f, 0.55f)
            drawCircle(Brush.radialGradient(listOf(glow.copy(0.30f + 0.2f * level), Color.Transparent), me, wPx * 0.22f), wPx * 0.22f, me)
            drawRoundRect(Color(0xFF1B2330), Offset(me.x - 26f, me.y - 18f), Size(52f, 36f), CornerRadius(12f))
            drawRoundRect(glow.copy(0.6f), Offset(me.x - 26f, me.y - 18f), Size(52f, 36f), CornerRadius(12f), style = Stroke(1.5f))

            // Visualizer (decorative, driven by the live loudness)
            if (look.surroundVisualizerEnabled && channels > 2) {
                drawVisualizer(look.surroundVisualizerStyle, glow, me, wPx, level, t)
            }

            // Speakers
            SPEAKER_SPOTS.forEach { spot ->
                val index = spot.channel.ordinal
                val playing = channels > 2 && (!spot.channel.sevenOneOnly || channels == 8)
                val c = channelColor(spot.channel, look)
                val centre = px(spot.u, spot.v)
                val gainFactor = 10f.pow(trimsDb[index] / 20f).coerceIn(0.25f, 2.5f)
                val energy = if (playing) (0.25f + 0.75f * level) * (0.6f + 0.4f * gainFactor.coerceAtMost(1.6f)) else 0f
                val perspective = 0.75f + 0.5f * spot.v

                if (playing) {
                    for (ring in 0..1) {
                        val r = (22f + ring * 14f + energy * 10f * (1 + ring)) * perspective
                        drawCircle(c.copy((0.55f - ring * 0.22f) * (0.5f + energy)), r, centre, style = Stroke(1.6f))
                    }
                }
                drawSpeaker(spot.channel == SurroundChannel.SW, centre, perspective, if (playing) c else Color(0xFF59606B), look.surroundShowSpeakerIcons)
            }
        }

        if (look.surroundShowLabels) {
            SPEAKER_SPOTS.forEach { spot ->
                val playing = channels > 2 && (!spot.channel.sevenOneOnly || channels == 8)
                val at = px(spot.u, spot.v)
                Text(
                    text = spot.channel.label,
                    color = if (playing) Color.White else Color.White.copy(0.4f),
                    fontSize = 10.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(30.dp)
                        .offset { IntOffset((at.x - 15.dp.toPx()).roundToInt(), (at.y - 46.dp.toPx() * (0.75f + 0.5f * spot.v)).roundToInt()) }
                )
            }
        }
    }
}

private fun DrawScope.drawSpeaker(isSub: Boolean, c: Offset, scale: Float, color: Color, detailed: Boolean) {
    if (!detailed) {
        drawCircle(color, 7f * scale, c)
        return
    }
    if (isSub) {
        val s = 30f * scale
        drawRoundRect(Color(0xFF1A1426), Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(6f * scale))
        drawRoundRect(color, Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(6f * scale), style = Stroke(2f))
        drawCircle(color.copy(0.8f), s * 0.28f, c, style = Stroke(2f))
        drawCircle(color, s * 0.1f, c)
    } else {
        val w = 22f * scale
        val h = 32f * scale
        drawRoundRect(Color(0xFF121820), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(5f * scale))
        drawRoundRect(color, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(5f * scale), style = Stroke(2f))
        drawCircle(color, w * 0.17f, Offset(c.x, c.y - h * 0.22f))
        drawCircle(color.copy(0.85f), w * 0.30f, Offset(c.x, c.y + h * 0.16f), style = Stroke(2f))
    }
}

private fun DrawScope.drawVisualizer(style: String, color: Color, centre: Offset, width: Float, level: Float, t: Float) {
    when (style) {
        "circle" -> {
            val r = width * 0.11f * (1f + 0.5f * level)
            drawCircle(color.copy(0.65f), r, centre, style = Stroke(2f))
            drawCircle(color.copy(0.30f), r * 1.35f, centre, style = Stroke(1.2f))
        }
        "spectrum" -> {
            val bars = 22
            val span = width * 0.5f
            for (i in 0 until bars) {
                val x = centre.x - span / 2 + span * i / (bars - 1)
                val h = (6f + 26f * level * (0.4f + 0.6f * abs(sin(t * 3f + i * 0.7f))))
                drawLine(color.copy(0.7f), Offset(x, centre.y + 34f), Offset(x, centre.y + 34f - h), 3f)
            }
        }
        else -> {
            val path = Path()
            val span = width * 0.5f
            val steps = 48
            for (i in 0..steps) {
                val x = centre.x - span / 2 + span * i / steps
                val y = centre.y + 34f + sin(i * 0.5f + t * 4f) * 14f * level
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color.copy(0.75f), style = Stroke(2.2f))
        }
    }
}

// ── Small controls ────────────────────────────────────────────────────────────────────────────────────────

private fun Modifier.glassPanel(primary: Color, secondary: Color, radius: androidx.compose.ui.unit.Dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.075f).copy(alpha = 0.66f))
    .background(Brush.verticalGradient(listOf(Color.White.copy(0.075f), Color.White.copy(0.012f))))
    .border(
        1.dp,
        Brush.linearGradient(listOf(primary.copy(0.42f), Color.White.copy(0.06f), secondary.copy(0.32f))),
        RoundedCornerShape(radius)
    )

@Composable
private fun LayoutButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = DspPaletteState.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) pal.primary.copy(0.16f) else Color.White.copy(0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) pal.primary else Color.White.copy(0.12f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) pal.primary else Color.White.copy(0.6f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (selected) Color.White else Color.White.copy(0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ModeChip(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = DspPaletteState.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) pal.primary.copy(0.16f) else Color.White.copy(0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) pal.primary else Color.White.copy(0.10f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) pal.primary else Color.White.copy(0.6f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (selected) Color.White else Color.White.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = DspPaletteState.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) pal.primary.copy(0.16f) else Color.White.copy(0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) pal.primary else Color.White.copy(0.10f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            color = Color.White.copy(if (!enabled) 0.3f else if (selected) 1f else 0.75f),
            fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
    }
}

@Composable
private fun TrimColumn(label: String, valueDb: Float, active: Boolean, color: Color, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp)) {
        val text = if (abs(valueDb) < 0.05f) "0.0" else String.format(java.util.Locale.US, "%+.1f", valueDb)
        Text(
            text,
            color = if (active) (if (abs(valueDb) < 0.05f) Color.White.copy(0.7f) else color) else Color.White.copy(0.28f),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        TrimSlider(valueDb, active, color, onChange)
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) Color.White else Color.White.copy(0.35f), fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

/** Vertical -12..+12 dB slider. Drag or tap; snaps to 0.5 dB. Double tap resets the channel to 0 dB. */
@Composable
private fun TrimSlider(valueDb: Float, active: Boolean, color: Color, onChange: (Float) -> Unit) {
    val latestOnChange by rememberUpdatedState(onChange)
    var heightPx by remember { mutableLongStateOf(1L) }
    fun toDb(y: Float): Float {
        val h = heightPx.toFloat().coerceAtLeast(1f)
        val frac = 1f - (y / h).coerceIn(0f, 1f)
        val raw = SURROUND_TRIM_MIN_DB + frac * (SURROUND_TRIM_MAX_DB - SURROUND_TRIM_MIN_DB)
        return (raw * 2f).roundToInt() / 2f
    }
    Canvas(
        modifier = Modifier
            .width(30.dp)
            .height(128.dp)
            .onSizeChanged { heightPx = it.height.toLong() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { latestOnChange(0f) },
                    onTap = { latestOnChange(toDb(it.y)) }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    latestOnChange(toDb(change.position.y))
                }
            }
    ) {
        val trackW = 5.dp.toPx()
        val x = size.width / 2f
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val frac = ((valueDb - SURROUND_TRIM_MIN_DB) / (SURROUND_TRIM_MAX_DB - SURROUND_TRIM_MIN_DB)).coerceIn(0f, 1f)
        val thumbY = bottom - (bottom - top) * frac
        val zeroY = bottom - (bottom - top) * 0.5f
        val tint = if (active) color else Color.White.copy(0.25f)

        drawRoundRect(Color.White.copy(0.10f), Offset(x - trackW / 2, top), Size(trackW, bottom - top), CornerRadius(trackW))
        // Fill from the 0 dB mark to the thumb
        val fillTop = minOf(thumbY, zeroY)
        val fillBottom = maxOf(thumbY, zeroY)
        drawRoundRect(
            Brush.verticalGradient(listOf(tint, tint.copy(0.55f)), fillTop, fillBottom.coerceAtLeast(fillTop + 1f)),
            Offset(x - trackW / 2, fillTop), Size(trackW, fillBottom - fillTop), CornerRadius(trackW)
        )
        drawLine(Color.White.copy(0.28f), Offset(x - 8.dp.toPx(), zeroY), Offset(x + 8.dp.toPx(), zeroY), 1f)
        drawCircle(Color(0xFF0B0F14), 8.dp.toPx(), Offset(x, thumbY))
        drawCircle(tint, 8.dp.toPx(), Offset(x, thumbY), style = Stroke(2.5f))
    }
}
