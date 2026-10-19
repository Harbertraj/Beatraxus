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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME
import com.beatraxus.app.model.SURROUND_TRIM_MAX_DB
import com.beatraxus.app.model.SURROUND_TRIM_MIN_DB
import com.beatraxus.app.model.SurroundChannel
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.sanitizeSurroundTrims
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.ui.theme.DspPaletteState
import com.beatraxus.app.ui.utils.LocalLiveAudioLevel
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Studio DSP > Surround page. Everything here only edits [com.beatraxus.app.model.DspConfig]; the audio side lives
 * in AudioEngine / AudioDspPipeline / SurroundUpmixer.
 */
@Composable
fun PremiumSurroundCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val look = uiState.appearance
    val pal = DspPaletteState.current
    // Settings > Appearance > Surround Screen: "Room glow" colour is the accent of the whole page.
    val accent = surroundAccent(look)

    val forcedOff = config.bypassAll || config.bitPerfectEnabled
    val fallback = uiState.dsp.surroundFallbackActive
    val liveChannels = uiState.dsp.surroundOutputChannels

    // Channels the room view shows as "playing": what the output really carries once audio flows, otherwise what
    // the chosen layout will produce. 2 means plain stereo, which lights the front L / R pair.
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

    val (statusText, statusColor) = when {
        forcedOff && config.surroundEnabled -> "BYPASSED" to Color(0xFFFFB74D)
        fallback -> "STEREO ONLY" to Color(0xFFFFB74D)
        !config.surroundEnabled -> "OFF" to Color.White.copy(0.45f)
        plannedChannels == 8 -> "7.1 ACTIVE" to accent
        plannedChannels == 6 -> "5.1 ACTIVE" to accent
        else -> "STEREO" to accent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(accent, pal.secondary, 22.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header: title + master on/off switch ──────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(0.30f), pal.secondary.copy(0.14f))))
                    .border(1.dp, accent.copy(0.55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.SurroundSound, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SURROUND",
                    color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 2.2.sp
                )
                Text("Immersive sound. Your way.", color = Color.White.copy(0.55f), fontSize = 10.sp)
            }
            PremiumSwitch(
                checked = config.surroundEnabled,
                onCheckedChange = { viewModel.setSurroundEnabled(it) },
                accentColor = accent
            )
        }

        // Everything below softly dims while surround is off, but stays editable so layouts can be pre-set.
        Column(
            modifier = Modifier.alpha(if (config.surroundEnabled) 1f else 0.6f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Stage view ────────────────────────────────────────────────────────────────────────────────
            SurroundStageView(plannedChannels, trims, look, uiState.isPlaying, statusText, statusColor)

            // ── Which speakers receive sound right now ────────────────────────────────────────────────────
            ActiveChannelStrip(plannedChannels, look)

            // ── Layout buttons: Stereo / 5.1 / 7.1 / Auto ─────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LayoutButton("Stereo", Icons.Rounded.Headphones, config.surroundLayout == SurroundLayout.STEREO, accent, Modifier.weight(1f)) {
                    viewModel.setSurroundLayout(SurroundLayout.STEREO)
                }
                LayoutButton("5.1", Icons.Rounded.SurroundSound, config.surroundLayout == SurroundLayout.FIVE_ONE, accent, Modifier.weight(1f)) {
                    viewModel.setSurroundLayout(SurroundLayout.FIVE_ONE)
                }
                LayoutButton("7.1", Icons.Rounded.SurroundSound, config.surroundLayout == SurroundLayout.SEVEN_ONE, accent, Modifier.weight(1f)) {
                    viewModel.setSurroundLayout(SurroundLayout.SEVEN_ONE)
                }
                LayoutButton("Auto", Icons.Rounded.AutoMode, config.surroundLayout == SurroundLayout.AUTO, accent, Modifier.weight(1f)) {
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

            // ── Channel levels ────────────────────────────────────────────────────────────────────────────
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
                    Icon(Icons.Rounded.Tune, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "CHANNEL LEVELS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.resetSurroundTrims() }.padding(4.dp)
                    ) {
                        Icon(Icons.Rounded.RestartAlt, null, tint = accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("RESET", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SurroundChannel.entries.forEachIndexed { index, channel ->
                        val active = config.surroundEnabled && !forcedOff && !fallback &&
                                plannedChannels > 2 && isChannelActive(channel, plannedChannels)
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

            // ── Presets ───────────────────────────────────────────────────────────────────────────────────
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
                        Icon(Icons.Rounded.Save, null, tint = accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SAVE AS CUSTOM 1", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
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
                                accent = accent,
                                modifier = Modifier.weight(1f)
                            ) { viewModel.applySurroundPreset(name) }
                        }
                        repeat(4 - rowNames.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// ── Stage view ────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Whether [channel] receives sound for an output carrying [outputChannels] PCM channels.
 * 2 = plain stereo (front L / R only), 6 = 5.1 (no back pair), 8 = 7.1 (everything).
 */
private fun isChannelActive(channel: SurroundChannel, outputChannels: Int): Boolean = when {
    outputChannels >= 8 -> true
    outputChannels >= 6 -> !channel.sevenOneOnly
    else -> channel == SurroundChannel.L || channel == SurroundChannel.R
}

/** Speaker position on the stage: angle in degrees from straight ahead (clockwise), radius as a share of the ring. */
private class SpeakerSpot(val channel: SurroundChannel, val angleDeg: Float, val radius: Float)

private val SPEAKER_SPOTS = listOf(
    SpeakerSpot(SurroundChannel.C, 0f, 0.92f),
    SpeakerSpot(SurroundChannel.L, -32f, 1f),
    SpeakerSpot(SurroundChannel.R, 32f, 1f),
    SpeakerSpot(SurroundChannel.SW, 62f, 1f),
    SpeakerSpot(SurroundChannel.SL, -90f, 1f),
    SpeakerSpot(SurroundChannel.SR, 90f, 1f),
    SpeakerSpot(SurroundChannel.BL, -148f, 1f),
    SpeakerSpot(SurroundChannel.BR, 148f, 1f)
)

/** Accent of the Surround screen: the user's "Room glow" colour, else the dual palette's primary. */
private fun surroundAccent(look: AppearanceConfig): Color =
    if (look.surroundColorGlow != 0) Color(look.surroundColorGlow) else DspPaletteState.current.primary

/** Live preview of the stage with every speaker on, used by Settings > Appearance > Surround Screen. */
@Composable
fun SurroundStagePreview(look: AppearanceConfig) {
    SurroundStageView(8, DEFAULT_SURROUND_TRIMS_DB, look, true, "PREVIEW", surroundAccent(look))
}

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

/**
 * Room floor plan, seen from above: the screen sits at the front, the listener in the middle, and every speaker
 * on its real angle (C 0°, L/R ±32°, SL/SR ±90°, BL/BR ±148°). A speaker that receives sound is filled with its
 * colour, sends a soft cone of sound towards the listener and radiates arcs that breathe with the music; a
 * speaker without a signal stays a dark, quiet cabinet.
 */
@Composable
private fun SurroundStageView(
    channels: Int,
    trimsDb: List<Float>,
    look: AppearanceConfig,
    isPlaying: Boolean,
    statusText: String,
    statusColor: Color
) {
    val pal = DspPaletteState.current
    val glow = surroundAccent(look)

    val bgInner: Color
    val bgOuter: Color
    when (look.surroundRoomTheme) {
        "studio" -> { bgInner = Color(0xFF2B3340); bgOuter = Color(0xFF10141B) }
        "living" -> { bgInner = Color(0xFF4A3322); bgOuter = Color(0xFF170E08) }
        else -> { bgInner = lerp(Color.Black, pal.secondary, 0.20f); bgOuter = lerp(Color.Black, pal.primary, 0.04f) }
    }

    // Frame clock: only the draw phase reads it, so the page does not recompose every frame.
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(look.surroundAnimated) {
        if (!look.surroundAnimated) { frame = 0L; return@LaunchedEffect }
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame = it }
        }
    }
    val levelProvider = LocalLiveAudioLevel.current
    val shape = RoundedCornerShape(26.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.02f)
            .clip(shape)
            .background(Color(0xFF07090D))
            .border(1.dp, Brush.verticalGradient(listOf(glow.copy(0.40f), Color.White.copy(0.05f))), shape)
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val cx = wPx * 0.5f
        val cy = hPx * 0.54f
        val rx = wPx * 0.385f
        val ry = hPx * 0.335f

        fun spotPos(spot: SpeakerSpot): Offset {
            val a = spot.angleDeg * PI.toFloat() / 180f
            return Offset(cx + sin(a) * rx * spot.radius, cy - cos(a) * ry * spot.radius)
        }

        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.02f)) {
            val k = density / 2.6f
            val t = frame / 1_000_000_000f
            val live = levelProvider?.invoke()
            val level = when {
                !isPlaying -> 0.18f
                !look.surroundAnimated -> 0.5f
                else -> (live ?: (0.35f + 0.25f * sin(t * 2.2f))).coerceIn(0f, 1f)
            }
            val me = Offset(cx, cy)

            // Room floor: a lit pool of the room theme, fading to black at the walls
            drawRect(Brush.radialGradient(listOf(bgInner.copy(0.85f), bgOuter, Color(0xFF07090D)), me, wPx * 0.72f))

            // Seating zone and the ring the speakers stand on (dashed)
            drawOval(
                Brush.radialGradient(listOf(glow.copy(0.16f + 0.08f * level), Color.Transparent), me, rx * 0.62f),
                Offset(cx - rx * 0.62f, cy - ry * 0.62f), Size(rx * 1.24f, ry * 1.24f)
            )
            drawOval(
                glow.copy(0.20f), Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f),
                style = Stroke(1.2f * k + 0.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * k, 7f * k)))
            )

            // Screen at the front of the room; it lights the floor below it while something plays
            if (look.surroundShowScreen) {
            val screenW = wPx * 0.36f
            val screenH = 6f * k
            val screenTop = hPx * 0.075f
            if (isPlaying) {
                drawRect(
                    Brush.verticalGradient(listOf(glow.copy(0.20f * (0.5f + level)), Color.Transparent), screenTop, screenTop + hPx * 0.2f),
                    Offset(cx - screenW * 0.62f, screenTop), Size(screenW * 1.24f, hPx * 0.2f)
                )
            }
            drawRoundRect(
                Color.White.copy(if (isPlaying) 0.75f else 0.28f),
                Offset(cx - screenW / 2f, screenTop), Size(screenW, screenH), CornerRadius(screenH / 2f)
            )
            }

            // Listener: a soft sweet-spot halo and a top-down head that faces the screen
            val phase = if (look.surroundAnimated) (t * 0.6f) % 1f else 0.35f
            if (look.surroundShowScreen) drawCircle(glow.copy((1f - phase) * 0.26f * (0.4f + level)), (14f + phase * 30f) * k, me, style = Stroke(1.4f))
            drawCircle(Color(0xFF10161F), 10f * k, me)
            drawCircle(glow.copy(0.9f), 10f * k, me, style = Stroke(1.8f * k))
            drawLine(glow.copy(0.9f), Offset(cx, cy - 10f * k), Offset(cx, cy - 15f * k), 2f * k)

            // Visualizer (decorative, driven by the live loudness)
            if (look.surroundVisualizerEnabled && channels > 2) {
                drawVisualizer(look.surroundVisualizerStyle, glow, Offset(cx, cy + 6f * k), wPx * 0.55f, level, t)
            }

            // Sound cones first so the cabinets sit on top
            if (look.surroundShowBeams) {
                SPEAKER_SPOTS.forEach { spot ->
                    if (!isChannelActive(spot.channel, channels)) return@forEach
                    val c = channelColor(spot.channel, look)
                    val at = spotPos(spot)
                    val dx = me.x - at.x
                    val dy = me.y - at.y
                    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val ux = dx / len
                    val uy = dy / len
                    // wedge: narrow at the speaker, wide at the listener
                    val px = -uy
                    val py = ux
                    val spread = 26f * k
                    val cone = Path().apply {
                        moveTo(at.x + ux * 14f * k, at.y + uy * 14f * k)
                        lineTo(me.x + px * spread, me.y + py * spread)
                        lineTo(me.x - px * spread, me.y - py * spread)
                        close()
                    }
                    val i = spot.channel.ordinal
                    val gain = 10f.pow(trimsDb[i] / 20f).coerceIn(0.25f, 2.5f)
                    val a = (0.10f + 0.16f * level) * (0.7f + 0.3f * gain.coerceAtMost(1.6f))
                    drawPath(cone, Brush.linearGradient(listOf(c.copy(a * 1.6f), c.copy(0f)), at, me))
                }
            }

            // Speakers
            SPEAKER_SPOTS.forEachIndexed { i, spot ->
                val index = spot.channel.ordinal
                val playing = isChannelActive(spot.channel, channels)
                val c = channelColor(spot.channel, look)
                val at = spotPos(spot)
                val gainFactor = 10f.pow(trimsDb[index] / 20f).coerceIn(0.25f, 2.5f)
                val energy = if (playing) (0.25f + 0.75f * level) * (0.6f + 0.4f * gainFactor.coerceAtMost(1.6f)) else 0f

                if (playing) {
                    drawCircle(Brush.radialGradient(listOf(c.copy(0.30f * (0.5f + energy)), Color.Transparent), at, 34f * k), 34f * k, at)
                    // three sound arcs that face the listener and drift outwards
                    val dir = Math.toDegrees(kotlin.math.atan2(me.y - at.y, me.x - at.x).toDouble()).toFloat()
                    if (look.surroundShowArcs) for (ring in 0..2) {
                        val ph = if (look.surroundAnimated) (t * 0.8f + i * 0.19f + ring / 3f) % 1f else 0.15f + ring * 0.3f
                        val r = (15f + ph * (14f + 12f * energy)) * k
                        drawArc(
                            c.copy((1f - ph) * 0.65f), dir - 38f, 76f, false,
                            Offset(at.x - r, at.y - r), Size(r * 2f, r * 2f),
                            style = Stroke(1.6f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        )
                    }
                }
                drawSpeaker(spot.channel, at, k, spot.angleDeg, if (playing) c else Color(0xFF59606B), playing, look.surroundShowSpeakerIcons)
            }
        }

        if (look.surroundShowLabels) {
            SPEAKER_SPOTS.forEach { spot ->
                val playing = isChannelActive(spot.channel, channels)
                val at = spotPos(spot)
                Text(
                    text = spot.channel.label,
                    color = if (playing) Color.White else Color.White.copy(0.32f),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(28.dp)
                        .offset { IntOffset((at.x - 14.dp.toPx()).roundToInt(), (at.y + 17.dp.toPx()).roundToInt()) }
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(if (playing) 0.45f else 0.25f))
                        .padding(vertical = 1.dp)
                )
            }
        }

        // Status pill: a dot in the state colour and a plain-language label
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(0.40f))
                .padding(start = 8.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(6.dp))
            Text(
                statusText.lowercase().replaceFirstChar { it.uppercase() },
                color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** One tile per speaker: lit in the speaker's colour when it receives sound, dark when it does not. */
@Composable
private fun ActiveChannelStrip(channels: Int, look: AppearanceConfig) {
    val order = listOf(
        SurroundChannel.L, SurroundChannel.C, SurroundChannel.R, SurroundChannel.SL,
        SurroundChannel.SR, SurroundChannel.BL, SurroundChannel.BR, SurroundChannel.SW
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        order.forEach { channel ->
            val on = isChannelActive(channel, channels)
            val c = channelColor(channel, look)
            val tile = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(tile)
                    .background(if (on) c.copy(0.14f) else Color.White.copy(0.04f))
                    .padding(top = 6.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (on) c else Color.White.copy(0.14f))
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    channel.label,
                    color = if (on) Color.White else Color.White.copy(0.30f),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
        }
    }
}

/** Top-down speaker: a cabinet with a driver cone, turned towards the listener ([angleDeg] rotates it into place). */
private fun DrawScope.drawSpeaker(
    channel: SurroundChannel, c: Offset, k: Float, angleDeg: Float, color: Color, playing: Boolean, detailed: Boolean
) {
    if (!detailed) {
        drawCircle(color, 7f * k, c)
        return
    }
    if (channel == SurroundChannel.SW) {
        val s = 26f * k
        val tl = Offset(c.x - s / 2, c.y - s / 2)
        drawRoundRect(Color(0xFF0E1118), tl, Size(s, s), CornerRadius(8f * k))
        drawRoundRect(color.copy(if (playing) 0.9f else 0.7f), tl, Size(s, s), CornerRadius(8f * k), style = Stroke(1.6f * k))
        drawCircle(color.copy(if (playing) 0.30f else 0.12f), s * 0.34f, c)
        drawCircle(color, s * 0.34f, c, style = Stroke(1.4f * k))
        drawCircle(color, s * 0.11f, c)
        return
    }
    val w = 22f * k
    val h = 16f * k
    rotate(angleDeg, c) {
        val tl = Offset(c.x - w / 2, c.y - h / 2)
        drawRoundRect(Color(0xFF0E1118), tl, Size(w, h), CornerRadius(5f * k))
        drawRoundRect(color.copy(if (playing) 0.9f else 0.7f), tl, Size(w, h), CornerRadius(5f * k), style = Stroke(1.5f * k))
        // driver cone on the baffle that faces the listener (unrotated: the bottom half)
        val dc = Offset(c.x, c.y + 1.5f * k)
        drawCircle(color.copy(if (playing) 0.30f else 0.10f), 4.6f * k, dc)
        drawCircle(color, 4.6f * k, dc, style = Stroke(1.2f * k))
        drawCircle(color, 1.5f * k, dc)
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
private fun LayoutButton(label: String, icon: ImageVector, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) accent.copy(0.16f) else Color.White.copy(0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else Color.White.copy(0.12f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) accent else Color.White.copy(0.6f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (selected) Color.White else Color.White.copy(0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) accent.copy(0.16f) else Color.White.copy(0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else Color.White.copy(0.10f), shape)
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
