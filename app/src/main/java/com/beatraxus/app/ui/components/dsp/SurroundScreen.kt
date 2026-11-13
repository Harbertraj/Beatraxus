package com.beatraxus.app.ui.components.dsp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import com.beatraxus.app.ui.theme.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.SURROUND_POSITION_MAX_DISTANCE
import com.beatraxus.app.model.SURROUND_POSITION_MIN_DISTANCE
import com.beatraxus.app.model.normalizeSurroundAngle
import com.beatraxus.app.model.activeSurroundPositionPreset
import com.beatraxus.app.model.SURROUND_ORBIT_DEFAULT_SECONDS
import com.beatraxus.app.model.SURROUND_ORBIT_MAX_SECONDS
import com.beatraxus.app.model.SURROUND_ORBIT_MIN_SECONDS
import com.beatraxus.app.model.SurroundLabKnob
import com.beatraxus.app.model.SurroundOrbitPattern
import com.beatraxus.app.model.advanceSurroundOrbitPhase
import com.beatraxus.app.model.knobValue
import com.beatraxus.app.model.sanitizeSurroundDistance
import com.beatraxus.app.model.smoothSurroundAngle
import com.beatraxus.app.model.surroundAngleGap
import com.beatraxus.app.model.surroundEffectiveTuning
import com.beatraxus.app.model.surroundGyroAngle
import com.beatraxus.app.model.surroundOrbitPoint
import com.beatraxus.app.model.surroundPhoneHeadingDeg
import com.beatraxus.app.model.surroundPresetDescription
import com.beatraxus.app.model.surroundMoveLevelsDb
import com.beatraxus.app.model.BUILT_IN_SURROUND_POSITIONS
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
import androidx.compose.runtime.collectAsState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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

    // 360° move. Orbit travels along the chosen style; Gyro follow keeps the sound fixed in the room while the phone
    // turns. Dragging the orb, tapping Center or leaving the mode stops both.
    var orbit by remember { mutableStateOf(false) }
    var gyro by remember { mutableStateOf(false) }
    var orbitStyle by rememberSaveable { mutableStateOf(0) }
    var orbitSeconds by rememberSaveable { mutableStateOf(SURROUND_ORBIT_DEFAULT_SECONDS) }
    var orbitBase by remember { mutableStateOf(config.surroundPositionDistance) }
    val latestConfig by rememberUpdatedState(config)
    LaunchedEffect(orbit, config.surroundMoveEnabled) {
        if (!orbit || !config.surroundMoveEnabled) return@LaunchedEffect
        val styles = SurroundOrbitPattern.entries
        val first = styles[orbitStyle.coerceIn(0, styles.size - 1)]
        // A circle or spiral carries on from where the orb is now; the other styles start at their own beginning.
        var phase = if (first == SurroundOrbitPattern.CIRCLE || first == SurroundOrbitPattern.SPIRAL) {
            latestConfig.surroundPositionAngleDeg / 360f
        } else 0f
        var last = androidx.compose.runtime.withFrameNanos { it }
        var acc = 0f
        try {
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                acc += (now - last) / 1_000_000_000f
                last = now
                if (acc >= ORBIT_STEP_S) {
                    phase = advanceSurroundOrbitPhase(phase, acc, orbitSeconds)
                    val style = styles[orbitStyle.coerceIn(0, styles.size - 1)]
                    val (angle, distance) = surroundOrbitPoint(style, phase, orbitBase)
                    viewModel.setSurroundPosition(angle, distance)
                    acc = 0f
                }
            }
        } finally {
            // Spiral and Figure 8 breathe in and out; when the orbit stops the distance goes back to the one the
            // user chose, so the slider and the next orbit start from it (not from wherever the breath stopped).
            viewModel.setSurroundDistance(orbitBase)
        }
    }

    // Gyro follow: the phone's heading when the mode starts is "straight ahead of the sound". Turning right moves
    // the sound to the left of you by the same angle, like head tracking. Uses the rotation-vector sensor.
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val motionSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    DisposableEffect(gyro, config.surroundMoveEnabled, motionSensor) {
        if (!gyro || !config.surroundMoveEnabled || sensorManager == null || motionSensor == null) {
            return@DisposableEffect onDispose { }
        }
        val rotation = FloatArray(9)
        val anchor = latestConfig.surroundPositionAngleDeg
        var startHeading: Float? = null
        var shown = anchor
        var lastPushNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val heading = surroundPhoneHeadingDeg(rotation) ?: return
                val start = startHeading ?: heading.also { startHeading = it }
                shown = smoothSurroundAngle(shown, surroundGyroAngle(anchor, heading, start), 0.25f)
                val c = latestConfig
                // At most 25 updates a second, and only when the sound really moved (saves the settings store).
                if (event.timestamp - lastPushNs >= 40_000_000L && surroundAngleGap(shown, c.surroundPositionAngleDeg) >= 0.4f) {
                    lastPushNs = event.timestamp
                    viewModel.setSurroundPosition(shown, c.surroundPositionDistance)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, motionSensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    val moveState = if (config.surroundMoveEnabled) {
        MoveState(config.surroundPositionAngleDeg, config.surroundPositionDistance) { angle, distance ->
            orbit = false
            gyro = false
            orbitBase = distance
            viewModel.setSurroundPosition(angle, distance)
        }
    } else null

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
                    color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.5.sp
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
            SurroundStageView(plannedChannels, trims, look, uiState.isPlaying, statusText, statusColor, moveState)

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

            // ── Channel levels only, or place the sound anywhere around you ───────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LayoutButton("Channel levels", Icons.Rounded.Tune, !config.surroundMoveEnabled, accent, Modifier.weight(1f)) {
                    orbit = false
                    gyro = false
                    viewModel.setSurroundMoveEnabled(false)
                }
                LayoutButton("360° move", Icons.Rounded.Sync, config.surroundMoveEnabled, accent, Modifier.weight(1f)) {
                    viewModel.setSurroundMoveEnabled(true)
                }
            }

            if (config.surroundMoveEnabled) {
                PositionPanel(
                    config = config,
                    accent = accent,
                    sevenOne = plannedChannels >= 8,
                    // While an orbit runs the live distance breathes (Spiral / Figure 8); the slider shows the
                    // distance the user chose, so the thumb stays where they put it.
                    distanceValue = if (orbit) orbitBase else config.surroundPositionDistance,
                    orbit = orbit,
                    onOrbit = {
                        if (it) gyro = false
                        orbit = it
                    },
                    gyro = gyro,
                    gyroAvailable = motionSensor != null,
                    onGyro = { if (it) orbit = false; gyro = it },
                    orbitStyle = orbitStyle,
                    onOrbitStyle = { orbitStyle = it },
                    orbitSeconds = orbitSeconds,
                    onOrbitSeconds = { orbitSeconds = it },
                    onDistance = {
                        orbitBase = sanitizeSurroundDistance(it)
                        // While orbiting, the orbit applies the new base itself on its next step.
                        if (!orbit) viewModel.setSurroundDistance(it)
                    },
                    onSpot = { name ->
                        orbit = false
                        gyro = false
                        BUILT_IN_SURROUND_POSITIONS.firstOrNull { it.name == name }?.let { orbitBase = it.distance }
                        viewModel.applySurroundPositionPreset(name)
                    },
                    onCenter = {
                        orbit = false
                        gyro = false
                        orbitBase = SURROUND_POSITION_MIN_DISTANCE
                        viewModel.resetSurroundPosition()
                    }
                )
            }

            // ── Channel levels ────────────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF050912), Color(0xFF0A1322))))
                    .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "CHANNEL LEVELS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
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

            // ── Upmix Lab: the matrix behind the surround mode, in your hands ─────────────────────────────
            UpmixLabPanel(
                config = config,
                accent = accent,
                onEnabled = { viewModel.setSurroundLabEnabled(it) },
                onKnob = { knob, value -> viewModel.setSurroundLabKnob(knob, value) },
                onReset = { viewModel.resetSurroundLab() }
            )

            // ── Presets ───────────────────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF050912), Color(0xFF0A1322))))
                    .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SurroundSound, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "PRESETS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
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
                val presetNote = surroundPresetDescription(config.surroundActivePreset)
                if (presetNote.isNotEmpty()) {
                    Text(presetNote, color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp)
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

/**
 * Distance of a speaker from the listener as a share of the ring, for its level trim in dB:
 * 0 dB = on the ring, +12 dB = 38% closer, -12 dB = 15% further out (kept inside the room).
 */
internal fun speakerDistanceFactor(trimDb: Float): Float {
    val t = trimDb.coerceIn(SURROUND_TRIM_MIN_DB, SURROUND_TRIM_MAX_DB) / SURROUND_TRIM_MAX_DB
    return if (t >= 0f) 1f - 0.38f * t else 1f - 0.15f * t
}

/**
 * The same room view for other screens (the video player's surround sheet). Uses the user's Surround Screen
 * appearance settings and the level trims shown in the sheet.
 */
@Composable
fun SurroundStageCard(
    channels: Int,
    trimsDb: List<Float>,
    isPlaying: Boolean,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val look by remember { com.beatraxus.app.repository.AppearancePreferences(context).appearanceConfig }
        .collectAsState(initial = AppearanceConfig())
    Box(modifier = modifier) {
        SurroundStageView(
            channels, sanitizeSurroundTrims(trimsDb), look, isPlaying,
            statusText, surroundAccent(look)
        )
    }
}

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
    statusColor: Color,
    move: MoveState? = null
) {
    val pal = DspPaletteState.current
    val glow = surroundAccent(look)
    val latestMove by rememberUpdatedState(move)

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
            .then(
                if (move != null) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                latestMove?.let { m ->
                                    val (a, d) = positionFromTouch(p.x, p.y, size.width.toFloat(), size.height.toFloat(), m.angleDeg)
                                    m.onMove(a, d)
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                latestMove?.let { m ->
                                    val (a, d) = positionFromTouch(
                                        change.position.x, change.position.y,
                                        size.width.toFloat(), size.height.toFloat(), m.angleDeg
                                    )
                                    m.onMove(a, d)
                                }
                            }
                        }
                } else Modifier
            )
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val cx = wPx * 0.5f
        val cy = hPx * 0.54f
        val rx = wPx * 0.385f
        val ry = hPx * 0.335f

        // A louder speaker stands closer to the listener, a quieter one further away, so every level change
        // (slider or preset) visibly moves its speaker.
        fun spotPos(spot: SpeakerSpot): Offset {
            val a = spot.angleDeg * PI.toFloat() / 180f
            val r = spot.radius * speakerDistanceFactor(trimsDb.getOrElse(spot.channel.ordinal) { 0f })
            return Offset(cx + sin(a) * rx * r, cy - cos(a) * ry * r)
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

            // 360° move: the sound orb, a dashed line back to the listener
            if (move != null) {
                val a = move.angleDeg * PI.toFloat() / 180f
                val orb = Offset(cx + sin(a) * rx * move.distance, cy - cos(a) * ry * move.distance)
                drawLine(
                    glow.copy(0.7f), me, orb, 1.5f * k + 0.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * k, 4f * k))
                )
                drawCircle(glow.copy(0.16f + 0.10f * level), 22f * k, orb)
                drawCircle(glow, 11f * k, orb)
                drawCircle(Color(0xFF04202A), 4f * k, orb)
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

        if (move != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(0.40f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text("Position", color = Color.White.copy(0.55f), fontSize = 10.sp)
                Text(
                    "${move.angleDeg.roundToInt() % 360}°",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** What the room view needs while 360° move is on: where the orb is, and what to do when the user places it. */
private class MoveState(
    val angleDeg: Float,
    val distance: Float,
    val onMove: (angleDeg: Float, distance: Float) -> Unit
)

/** Auto-orbit moves the sound 20 times a second; how long one cycle takes is the Orbit speed slider. */
private const val ORBIT_STEP_S = 0.05f

/**
 * Touch point in the room view -> sound position. Matches the room geometry in [SurroundStageView]
 * (centre at 54% height, ring radii 38.5% of the width / 33.5% of the height). Touching right on the listener
 * keeps the current angle, since "straight ahead of nothing" has no direction.
 */
private fun positionFromTouch(x: Float, y: Float, w: Float, h: Float, currentAngle: Float): Pair<Float, Float> {
    val dx = (x - w * 0.5f) / (w * 0.385f)
    val dy = -(y - h * 0.54f) / (h * 0.335f)
    val distance = sqrt(dx * dx + dy * dy)
    val angle = if (distance < 0.05f) currentAngle
    else normalizeSurroundAngle(Math.toDegrees(atan2(dx, dy).toDouble()).toFloat())
    return angle to sanitizeSurroundDistance(distance)
}

/** Distance, auto-orbit / centre buttons and the per-speaker effect of the position, for the 360° move mode. */
@Composable
private fun PositionPanel(
    config: DspConfig,
    accent: Color,
    sevenOne: Boolean,
    distanceValue: Float,
    orbit: Boolean,
    onOrbit: (Boolean) -> Unit,
    gyro: Boolean,
    gyroAvailable: Boolean,
    onGyro: (Boolean) -> Unit,
    orbitStyle: Int,
    onOrbitStyle: (Int) -> Unit,
    orbitSeconds: Float,
    onOrbitSeconds: (Float) -> Unit,
    onDistance: (Float) -> Unit,
    onSpot: (String) -> Unit,
    onCenter: () -> Unit
) {
    val distance = sanitizeSurroundDistance(distanceValue)
    val percent = (distance * 100f).roundToInt()
    val offsets = surroundMoveLevelsDb(config.surroundPositionAngleDeg, config.surroundPositionDistance, sevenOne)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF050912), Color(0xFF0A1322))))
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sync, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "SOUND POSITION", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
            )
            Text(
                (if (percent >= 60) "Wide " else if (percent >= 35) "Mid " else "Close ") + "$percent%",
                color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black
            )
        }
        Text("Drag the glowing orb in the room to place the sound anywhere around you. Close surrounds you, Wide puts it right at that direction.", color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp)
        Slider(
            value = distance,
            onValueChange = onDistance,
            valueRange = SURROUND_POSITION_MIN_DISTANCE..SURROUND_POSITION_MAX_DISTANCE,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(0.12f)
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Close · all around", color = Color.White.copy(0.55f), fontSize = 10.sp)
            Text("Wide · placed", color = Color.White.copy(0.55f), fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LayoutButton(if (orbit) "Orbit: on" else "Orbit", Icons.Rounded.Autorenew, orbit, accent, Modifier.weight(1f)) {
                onOrbit(!orbit)
            }
            LayoutButton(
                if (!gyroAvailable) "No sensor" else if (gyro) "Gyro: on" else "Gyro",
                Icons.Rounded.Headphones, gyro, accent, Modifier.weight(1f)
            ) { if (gyroAvailable) onGyro(!gyro) }
            LayoutButton("Center", Icons.Rounded.CenterFocusStrong, false, accent, Modifier.weight(1f)) { onCenter() }
        }
        if (gyro) {
            Text(
                "Hold the phone in front of you. When you turn, the sound stays where it is in the room.",
                color = accent.copy(0.85f), fontSize = 10.sp, lineHeight = 14.sp
            )
        }
        Text(
            "ORBIT STYLE", color = Color.White.copy(0.55f), fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SurroundOrbitPattern.entries.forEachIndexed { index, style ->
                PresetChip(style.label, orbitStyle == index, true, accent, Modifier.weight(1f)) { onOrbitStyle(index) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ORBIT SPEED", color = Color.White.copy(0.55f), fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Text("${orbitSeconds.roundToInt()} s per turn", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Slider(
            // Right = faster, so the slider shows the opposite of the seconds per turn.
            value = SURROUND_ORBIT_MIN_SECONDS + SURROUND_ORBIT_MAX_SECONDS - orbitSeconds,
            onValueChange = { onOrbitSeconds(SURROUND_ORBIT_MIN_SECONDS + SURROUND_ORBIT_MAX_SECONDS - it) },
            valueRange = SURROUND_ORBIT_MIN_SECONDS..SURROUND_ORBIT_MAX_SECONDS,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(0.12f)
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Slower", color = Color.White.copy(0.55f), fontSize = 10.sp)
            Text("Faster", color = Color.White.copy(0.55f), fontSize = 10.sp)
        }
        Text(
            "QUICK SPOTS", color = Color.White.copy(0.55f), fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        val activeSpot = config.activeSurroundPositionPreset()
        BUILT_IN_SURROUND_POSITIONS.map { it.name }.chunked(3).forEach { rowNames ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowNames.forEach { name ->
                    PresetChip(name, activeSpot == name, true, accent, Modifier.weight(1f)) { onSpot(name) }
                }
                repeat(3 - rowNames.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            "SPEAKER LEVELS WHILE MOVING (dB, 0 = normal stereo)", color = Color.White.copy(0.55f), fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SurroundChannel.entries.forEachIndexed { i, channel ->
                val db = offsets[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                    Text(channel.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (abs(db) < 0.05f) "0.0" else String.format(java.util.Locale.US, "%+.1f", db),
                        color = if (db > 0.05f) accent else Color.White.copy(0.45f),
                        fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Upmix Lab: the five numbers behind Movie / Music / Game, as sliders. Moving any slider takes over from the mode
 * (the sliders start where the mode is, so nothing jumps); picking a preset hands control back to the mode.
 * Only a stereo song that is upmixed is affected; a real 5.1 / 7.1 soundtrack keeps its own channels.
 */
@Composable
private fun UpmixLabPanel(
    config: DspConfig,
    accent: Color,
    onEnabled: (Boolean) -> Unit,
    onKnob: (SurroundLabKnob, Float) -> Unit,
    onReset: () -> Unit
) {
    val tuning = config.surroundEffectiveTuning()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF050912), Color(0xFF0A1322))))
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "UPMIX LAB", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onReset() }.padding(4.dp)
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("RESET", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(8.dp))
            PremiumSwitch(checked = config.surroundTuneEnabled, onCheckedChange = onEnabled, accentColor = accent)
        }
        Text(
            if (config.surroundTuneEnabled) "Your tuning replaces the ${config.surroundMode.displayName} mode. Picking a preset hands control back to the mode."
            else "Showing the ${config.surroundMode.displayName} mode. Move a slider to make it your own.",
            color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp
        )
        SurroundLabKnob.entries.forEach { knob ->
            val value = tuning.knobValue(knob)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    knob.label.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp, modifier = Modifier.weight(1f)
                )
                Text(knob.display(value), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Slider(
                value = value,
                onValueChange = { onKnob(knob, it) },
                valueRange = knob.min..knob.max,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(0.12f)
                )
            )
            Text(knob.hint, color = Color.White.copy(0.45f), fontSize = 10.sp, lineHeight = 14.sp)
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
    .background(Brush.verticalGradient(listOf(Color(0xFF0B1424).copy(alpha = 0.88f), Color(0xFF070B14).copy(alpha = 0.92f))))
    .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.07f), Color.Transparent), radius = 700f))
    .border(
        1.dp,
        Brush.linearGradient(listOf(primary.copy(0.55f), primary.copy(0.10f), secondary.copy(0.45f))),
        RoundedCornerShape(radius)
    )

@Composable
private fun LayoutButton(label: String, icon: ImageVector, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(46.dp)
            .then(if (selected) Modifier.shadow(10.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (selected) accent.copy(0.18f) else Color(0xFF0B1424).copy(0.9f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else Color.White.copy(0.12f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) accent else Color.White.copy(0.6f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) Color.White else Color.White.copy(0.7f), fontSize = 12.sp, fontWeight = if (selected) FontWeight.Black else FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(38.dp)
            .then(if (selected && enabled) Modifier.shadow(8.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (selected) accent.copy(0.18f) else Color(0xFF0B1424).copy(0.9f))
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
        val text = if (abs(valueDb) < 0.05f) "0.0" else String.format(java.util.Locale.US, "%+.1f", valueDb)
        val valueColor = if (active) (if (abs(valueDb) < 0.05f) Color.White.copy(0.7f) else color) else Color.White.copy(0.28f)
        TrimSlider(valueDb, active, color, onChange)
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (active) Color.White else Color.White.copy(0.35f), fontSize = 11.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) color.copy(0.10f) else Color.White.copy(0.04f))
                .border(1.dp, if (active) color.copy(0.55f) else Color.White.copy(0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 3.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = valueColor, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
        }
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
        val top = 10.dp.toPx()
        val bottom = size.height - 10.dp.toPx()
        val frac = ((valueDb - SURROUND_TRIM_MIN_DB) / (SURROUND_TRIM_MAX_DB - SURROUND_TRIM_MIN_DB)).coerceIn(0f, 1f)
        val thumbY = bottom - (bottom - top) * frac
        val zeroY = bottom - (bottom - top) * 0.5f
        val tint = if (active) color else Color.White.copy(0.25f)

        // Recessed capsule track with scale ticks
        val capW = 16.dp.toPx()
        drawRoundRect(Color(0xFF050912), Offset(x - capW / 2, 0f), Size(capW, size.height), CornerRadius(capW / 2))
        drawRoundRect(Color.White.copy(0.08f), Offset(x - capW / 2, 0f), Size(capW, size.height), CornerRadius(capW / 2), style = Stroke(1.dp.toPx()))
        for (i in 0..4) {
            val ty = top + (bottom - top) * i / 4f
            val tw = if (i == 2) 5.dp.toPx() else 3.dp.toPx()
            drawLine(Color.White.copy(if (i == 2) 0.35f else 0.14f), Offset(x - tw, ty), Offset(x + tw, ty), 1.dp.toPx())
        }
        // Fill from the 0 dB mark to the thumb
        val fillTop = minOf(thumbY, zeroY)
        val fillBottom = maxOf(thumbY, zeroY)
        drawRoundRect(
            Brush.verticalGradient(listOf(tint, tint.copy(0.55f)), fillTop, fillBottom.coerceAtLeast(fillTop + 1f)),
            Offset(x - trackW / 2, fillTop), Size(trackW, fillBottom - fillTop), CornerRadius(trackW)
        )
        if (active) drawCircle(Brush.radialGradient(listOf(tint.copy(0.35f), Color.Transparent), Offset(x, thumbY), 20.dp.toPx()), 20.dp.toPx(), Offset(x, thumbY))
        // Metal fader cap with a neon centre line
        val capH = 16.dp.toPx()
        val knobW = 28.dp.toPx()
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF6B7380), Color(0xFF3A404B), Color(0xFF1E222A)), thumbY - capH / 2, thumbY + capH / 2),
            Offset(x - knobW / 2, thumbY - capH / 2), Size(knobW, capH), CornerRadius(7.dp.toPx())
        )
        drawRoundRect(tint.copy(0.8f), Offset(x - knobW / 2, thumbY - capH / 2), Size(knobW, capH), CornerRadius(7.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawLine(tint, Offset(x - 8.dp.toPx(), thumbY), Offset(x + 8.dp.toPx(), thumbY), 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
