package com.beatraxus.app.ui.components.dsp

import com.beatraxus.app.ui.theme.bgTone
import com.beatraxus.app.ui.theme.BgGradBottom
import com.beatraxus.app.ui.theme.BgGradTop
import com.beatraxus.app.ui.theme.BgPanel
import com.beatraxus.app.ui.theme.BgPanelDeep
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.beatraxus.app.model.SURROUND_ALL_SLOT_NAMES
import com.beatraxus.app.model.surroundSlotFilled
import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME
import com.beatraxus.app.model.SURROUND_TRIM_MAX_DB
import com.beatraxus.app.model.SURROUND_TRIM_MIN_DB
import com.beatraxus.app.model.SurroundChannel
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.sanitizeSurroundTrims
import com.beatraxus.app.model.SURROUND_DISTANCE_DEFAULT_M
import com.beatraxus.app.model.sanitizeSurroundDistances
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

    // 360° move (orbit / gyro follow): state and sensor / frame loops live in a controller shared with the video sheet.
    val move = rememberSurroundMoveController(
        config,
        onPosition = { a, d -> viewModel.setSurroundPosition(a, d) },
        onDistance = { viewModel.setSurroundDistance(it) }
    )
    val moveState = if (config.surroundMoveEnabled) {
        MoveState(config.surroundPositionAngleDeg, config.surroundPositionDistance) { angle, distance ->
            move.stop()
            move.orbitBase = distance
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

        // Everything below softly dims while surround is off. Layout and preset chips stay tappable so a layout can be
        // pre-set, but every bar, fader, slider and the room orb is locked until the master switch is on.
        CompositionLocalProvider(LocalSurroundControlsEnabled provides config.surroundEnabled) {
        Column(
            modifier = Modifier.alpha(if (config.surroundEnabled) 1f else 0.6f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Shared by the Studio DSP surround page and the video player's surround sheet ──────────────────────────────

/**
 * False while the Surround master switch is off: every bar, fader, slider and the room orb ignores touches (they
 * would change values that nothing is using). Defaults to true outside the surround screens.
 */
internal val LocalSurroundControlsEnabled = compositionLocalOf { true }

/** State of the 360° move helpers: auto orbit, gyro follow and the distance the user chose for an orbit. */
@Stable
internal class SurroundMoveController(initialDistance: Float, val gyroAvailable: Boolean) {
    var orbit by mutableStateOf(false)
    var gyro by mutableStateOf(false)
    var orbitStyle by mutableStateOf(0)
    var orbitSeconds by mutableStateOf(SURROUND_ORBIT_DEFAULT_SECONDS)
    var orbitBase by mutableStateOf(initialDistance)

    /** Dragging the orb, tapping Center or leaving the mode stops both helpers. */
    fun stop() {
        orbit = false
        gyro = false
    }
}

/**
 * Creates the move controller and runs its two loops. Orbit travels along the chosen style; Gyro follow keeps the
 * sound fixed in the room while the phone turns (the heading when the mode starts is "straight ahead of the
 * sound"; uses the rotation-vector sensor). [onPosition] / [onDistance] write the settings.
 */
@Composable
internal fun rememberSurroundMoveController(
    config: DspConfig,
    onPosition: (Float, Float) -> Unit,
    onDistance: (Float) -> Unit
): SurroundMoveController {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val motionSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    val c = remember(motionSensor) { SurroundMoveController(config.surroundPositionDistance, motionSensor != null) }
    val latestConfig by rememberUpdatedState(config)
    val latestOnPosition by rememberUpdatedState(onPosition)
    val latestOnDistance by rememberUpdatedState(onDistance)

    LaunchedEffect(c.orbit, config.surroundMoveEnabled) {
        if (!c.orbit || !config.surroundMoveEnabled) return@LaunchedEffect
        val styles = SurroundOrbitPattern.entries
        val first = styles[c.orbitStyle.coerceIn(0, styles.size - 1)]
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
                    phase = advanceSurroundOrbitPhase(phase, acc, c.orbitSeconds)
                    val style = styles[c.orbitStyle.coerceIn(0, styles.size - 1)]
                    val (angle, distance) = surroundOrbitPoint(style, phase, c.orbitBase)
                    latestOnPosition(angle, distance)
                    acc = 0f
                }
            }
        } finally {
            // Spiral and Figure 8 breathe in and out; when the orbit stops the distance goes back to the one the
            // user chose, so the slider and the next orbit start from it (not from wherever the breath stopped).
            latestOnDistance(c.orbitBase)
        }
    }

    DisposableEffect(c.gyro, config.surroundMoveEnabled, motionSensor) {
        if (!c.gyro || !config.surroundMoveEnabled || sensorManager == null || motionSensor == null) {
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
                val cfg = latestConfig
                // At most 25 updates a second, and only when the sound really moved (saves the settings store).
                if (event.timestamp - lastPushNs >= 40_000_000L && surroundAngleGap(shown, cfg.surroundPositionAngleDeg) >= 0.4f) {
                    lastPushNs = event.timestamp
                    latestOnPosition(shown, cfg.surroundPositionDistance)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, motionSensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return c
}

/** The "Sound position" card of the 360° move mode, wired to a [SurroundMoveController]. */
@Composable
internal fun SurroundMoveSection(
    config: DspConfig,
    accent: Color,
    sevenOne: Boolean,
    move: SurroundMoveController,
    onDistance: (Float) -> Unit,
    onSpot: (String) -> Unit,
    onCenter: () -> Unit
) {
    PositionPanel(
        config = config,
        accent = accent,
        sevenOne = sevenOne,
        // While an orbit runs the live distance breathes (Spiral / Figure 8); the slider shows the distance the
        // user chose, so the thumb stays where they put it.
        distanceValue = if (move.orbit) move.orbitBase else config.surroundPositionDistance,
        orbit = move.orbit,
        onOrbit = {
            if (it) move.gyro = false
            move.orbit = it
        },
        gyro = move.gyro,
        gyroAvailable = move.gyroAvailable,
        onGyro = { if (it) move.orbit = false; move.gyro = it },
        orbitStyle = move.orbitStyle,
        onOrbitStyle = { move.orbitStyle = it },
        orbitSeconds = move.orbitSeconds,
        onOrbitSeconds = { move.orbitSeconds = it },
        onDistance = {
            move.orbitBase = sanitizeSurroundDistance(it)
            // While orbiting, the orbit applies the new base itself on its next step.
            if (!move.orbit) onDistance(it)
        },
        onSpot = { name ->
            move.stop()
            BUILT_IN_SURROUND_POSITIONS.firstOrNull { it.name == name }?.let { move.orbitBase = it.distance }
            onSpot(name)
        },
        onCenter = {
            move.stop()
            move.orbitBase = SURROUND_POSITION_MIN_DISTANCE
            onCenter()
        }
    )
}

// ── Stage view ────────────────────────────────────────────────────────────────────────────────
            SurroundStageView(
                plannedChannels, trims, look, uiState.isPlaying, statusText, statusColor, moveState,
                distancesM = if (config.surroundCalibrationEnabled) config.surroundSpeakerDistancesM else null
            )

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
                !config.surroundEnabled -> "Surround is off. Audio plays exactly as before. Turn it on to use the level bars and sliders."
                else -> null
            }
            if (note != null) {
                Text(note, color = Color.White.copy(0.55f), fontSize = 10.sp, lineHeight = 14.sp)
            }

            // ── Live speaker levels, A/B hold ─────────────────────────────────────────────────────────────
            if (config.surroundEnabled && plannedChannels > 2) {
                SurroundMeterStrip(viewModel, running = uiState.isPlaying && !forcedOff, channels = plannedChannels, accent = accent)
                HoldOriginalButton(accent, enabled = uiState.isPlaying) { viewModel.setSurroundHoldOriginal(it) }
            }

            // ── Channel levels only, or place the sound anywhere around you ───────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LayoutButton("Channel levels", Icons.Rounded.Tune, !config.surroundMoveEnabled, accent, Modifier.weight(1f)) {
                    move.stop()
                    viewModel.setSurroundMoveEnabled(false)
                }
                LayoutButton("360° move", Icons.Rounded.Sync, config.surroundMoveEnabled, accent, Modifier.weight(1f)) {
                    viewModel.setSurroundMoveEnabled(true)
                }
            }

            if (config.surroundMoveEnabled) {
                SurroundMoveSection(
                    config = config,
                    accent = accent,
                    sevenOne = plannedChannels >= 8,
                    move = move,
                    onDistance = { viewModel.setSurroundDistance(it) },
                    onSpot = { viewModel.applySurroundPositionPreset(it) },
                    onCenter = { viewModel.resetSurroundPosition() }
                )
            }

            // ── Channel levels ────────────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(BgGradTop, BgGradBottom)))
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
            ) {
                SpeakerSetupPanel(
                    config = config,
                    accent = accent,
                    sevenOne = plannedChannels >= 8 || config.surroundLayout == SurroundLayout.SEVEN_ONE,
                    embedded = true,
                    onTest = { viewModel.setSurroundTestChannel(it) },
                    onCalibration = { viewModel.setSurroundCalibration(it) },
                    onDistance = { i, m -> viewModel.setSurroundSpeakerDistance(i, m) },
                    onResetDistances = { viewModel.resetSurroundSpeakerDistances() },
                    onBassManagement = { viewModel.setSurroundBassManagement(it) }
                )
            }

            // ── Presets ───────────────────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(BgGradTop, BgGradBottom)))
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
                val names = BUILT_IN_SURROUND_PRESETS.map { it.name } + SURROUND_ALL_SLOT_NAMES
                names.chunked(4).forEach { rowNames ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowNames.forEach { name ->
                            val isCustomSlot = name in SURROUND_ALL_SLOT_NAMES
                            val available = !isCustomSlot || config.surroundSlotFilled(name)
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
                PresetSlotsRow(
                    config = config,
                    accent = accent,
                    onSave = { viewModel.saveSurroundSlot(it) },
                    exportCode = { viewModel.surroundPresetCodeText() },
                    onImport = { viewModel.importSurroundPresetCode(it) }
                )
            }

            // ── Smart steering and speaker setup ──────────────────────────────────────────────────────────
            SmartSteeringPanel(
                config, accent,
                onEnabled = { viewModel.setSurroundSmartSteering(it) },
                onAmount = { viewModel.setSurroundSmartSteeringAmount(it) }
            )
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
 * Distance of a speaker from the listener on the stage for its real distance in metres (Speaker setup > Distance
 * delay): [SURROUND_DISTANCE_DEFAULT_M] = on the ring, nearer speakers come clearly closer, farther ones step back
 * a little (the room is small, so the far side is compressed).
 */
internal fun speakerMetresFactor(metres: Float): Float {
    val ratio = (metres / SURROUND_DISTANCE_DEFAULT_M).coerceIn(0.2f, 4f)
    return if (ratio <= 1f) 0.42f + 0.58f * ratio else 1f + 0.18f * ((ratio - 1f) / 3f)
}

private const val STAGE_SPEAKER_MIN_R = 0.5f
private const val STAGE_SPEAKER_MAX_R = 1.22f
private const val STAGE_SPEAKER_MIN_GAP = 0.25f

/**
 * Floor-plan position (x sideways, y towards the screen, in ring units) of every speaker in [SPEAKER_SPOTS] order.
 * A louder speaker stands closer ([trimsDb]); with [distancesM] each speaker's real distance moves it too. Cabinets
 * that would end up on top of each other are pushed apart (fixed number of passes, so dragging a slider never
 * makes them jump), and everything stays inside the room.
 */
internal fun surroundStageLayout(trimsDb: List<Float>, distancesM: List<Float>?): List<Offset> {
    val metres = distancesM?.let { sanitizeSurroundDistances(it) }
    val n = SPEAKER_SPOTS.size
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for ((i, spot) in SPEAKER_SPOTS.withIndex()) {
        val a = spot.angleDeg * PI.toFloat() / 180f
        var r = spot.radius * speakerDistanceFactor(trimsDb.getOrElse(spot.channel.ordinal) { 0f })
        if (metres != null) r *= speakerMetresFactor(metres[spot.channel.ordinal])
        r = r.coerceIn(STAGE_SPEAKER_MIN_R, STAGE_SPEAKER_MAX_R)
        xs[i] = sin(a) * r
        ys[i] = cos(a) * r
    }
    for (pass in 0 until 12) {
        var moved = false
        for (i in 0 until n) for (j in i + 1 until n) {
            var dx = xs[j] - xs[i]
            var dy = ys[j] - ys[i]
            var d = sqrt(dx * dx + dy * dy)
            if (d >= STAGE_SPEAKER_MIN_GAP) continue
            if (d < 1e-4f) {
                // Exactly on top of each other: separate sideways, by the speaker's own side of the room.
                dx = if (SPEAKER_SPOTS[j].angleDeg >= SPEAKER_SPOTS[i].angleDeg) 1f else -1f
                dy = 0f
                d = 1f
            }
            val push = (STAGE_SPEAKER_MIN_GAP - d) / 2f + 0.002f
            val ux = dx / d
            val uy = dy / d
            xs[i] -= ux * push; ys[i] -= uy * push
            xs[j] += ux * push; ys[j] += uy * push
            moved = true
        }
        for (i in 0 until n) {
            val r = sqrt(xs[i] * xs[i] + ys[i] * ys[i])
            if (r > STAGE_SPEAKER_MAX_R) {
                xs[i] *= STAGE_SPEAKER_MAX_R / r; ys[i] *= STAGE_SPEAKER_MAX_R / r
            } else if (r < STAGE_SPEAKER_MIN_R && r > 1e-4f) {
                xs[i] *= STAGE_SPEAKER_MIN_R / r; ys[i] *= STAGE_SPEAKER_MIN_R / r
            }
        }
        if (!moved) break
    }
    return List(n) { Offset(xs[it], ys[it]) }
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
    modifier: Modifier = Modifier,
    /** Speaker distances in metres (L R C SL SR BL BR SW); null = ignore them (Distance delay is off). */
    distancesM: List<Float>? = null,
    /** 360° move: where the sound orb sits and what to do when it is placed. Null = no orb. */
    moveAngleDeg: Float = 0f,
    moveDistance: Float = SURROUND_POSITION_MIN_DISTANCE,
    onMove: ((angleDeg: Float, distance: Float) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val look by remember { com.beatraxus.app.repository.AppearancePreferences(context).appearanceConfig }
        .collectAsState(initial = AppearanceConfig())
    Box(modifier = modifier) {
        SurroundStageView(
            channels, sanitizeSurroundTrims(trimsDb), look, isPlaying,
            statusText, surroundAccent(look),
            move = onMove?.let { MoveState(moveAngleDeg, moveDistance, it) },
            distancesM = distancesM
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
    move: MoveState? = null,
    distancesM: List<Float>? = null
) {
    val pal = DspPaletteState.current
    val controlsOn = LocalSurroundControlsEnabled.current
    // Where the speakers stand: level trims and (with Distance delay on) the real distances move them. A change
    // glides over a fraction of a second instead of jumping.
    val targetLayout = remember(trimsDb, distancesM) { surroundStageLayout(trimsDb, distancesM) }
    var shownLayout by remember { mutableStateOf(targetLayout) }
    LaunchedEffect(targetLayout) {
        val from = shownLayout
        if (from == targetLayout) return@LaunchedEffect
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(220)) {
            val f = value
            shownLayout = List(targetLayout.size) { i ->
                Offset(from[i].x + (targetLayout[i].x - from[i].x) * f, from[i].y + (targetLayout[i].y - from[i].y) * f)
            }
        }
    }
    val glow = surroundAccent(look)
    val latestMove by rememberUpdatedState(move)

    val bgInner: Color
    val bgOuter: Color
    when (look.surroundRoomTheme) {
        "studio" -> { bgInner = Color(0xFF2B3340); bgOuter = bgTone(0xFF10141B) }
        "living" -> { bgInner = Color(0xFF4A3322); bgOuter = bgTone(0xFF170E08) }
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
            .background(bgTone(0xFF07090D))
            .border(1.dp, Brush.verticalGradient(listOf(glow.copy(0.40f), Color.White.copy(0.05f))), shape)
            .then(
                if (move != null && controlsOn) {
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
        val cy = hPx * STAGE_CY
        val rx = wPx * STAGE_RX
        val ry = hPx * STAGE_RY
        // Floor plan (fx sideways, fy towards the screen) -> screen. Far things shrink, near things grow.
        fun floorPoint(fx: Float, fy: Float): Offset {
            val pp = stageDepthScale(fy)
            return Offset(cx + fx * rx * pp, cy - fy * ry * pp)
        }
        fun ringPath(r: Float): Path = Path().apply {
            val n = 72
            for (i in 0..n) {
                val a = 2f * PI.toFloat() * i / n
                val o = floorPoint(sin(a) * r, cos(a) * r)
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }

        // A louder speaker stands closer to the listener, a quieter one further away, so every level change
        // (slider or preset) visibly moves its speaker.
        fun spotPos(spot: SpeakerSpot): Offset {
            val p = shownLayout[SPEAKER_SPOTS.indexOf(spot)]
            return floorPoint(p.x, p.y)
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

            // Cinema room: walls, ceiling coves, glossy floor, curved screen
            drawCinemaRoom(
                ::floorPoint, wPx, hPx, cx, cy, rx, k, glow, bgInner, level, isPlaying, look.surroundShowScreen
            )

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
                    val spread = 34f * k
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

            // Cinema seats with the listener in the middle one
            drawCinemaSeating(cx, cy, k, glow)

            // Speakers: free-standing 3D cabinets, drawn far to near so closer ones overlap the distant ones
            SPEAKER_SPOTS.withIndex().sortedByDescending { spotPos(it.value).y }.forEach { (i, spot) ->
                val index = spot.channel.ordinal
                val playing = isChannelActive(spot.channel, channels)
                val c = channelColor(spot.channel, look)
                val at = spotPos(spot)
                val ps = (0.6f + 0.5f * stageDepthScale(-(at.y - cy) / ry)) // size follows depth
                val ks = k * ps
                val gainFactor = 10f.pow(trimsDb[index] / 20f).coerceIn(0.25f, 2.5f)
                val energy = if (playing) (0.25f + 0.75f * level) * (0.6f + 0.4f * gainFactor.coerceAtMost(1.6f)) else 0f
                val bodyH = if (spot.channel == SurroundChannel.SW) 28f * ks else 42f * ks
                val body = Offset(at.x, at.y - bodyH * 0.5f)

                if (playing) {
                    drawOval(
                        Brush.radialGradient(listOf(c.copy(0.30f * (0.5f + energy)), Color.Transparent), at, 36f * ks),
                        Offset(at.x - 36f * ks, at.y - 18f * ks), Size(72f * ks, 36f * ks)
                    )
                    val dir = Math.toDegrees(kotlin.math.atan2(me.y - body.y, me.x - body.x).toDouble()).toFloat()
                    if (look.surroundShowArcs) for (ring in 0..2) {
                        val ph = if (look.surroundAnimated) (t * 0.8f + i * 0.19f + ring / 3f) % 1f else 0.15f + ring * 0.3f
                        val r = (17f + ph * (14f + 12f * energy)) * ks
                        drawArc(
                            c.copy((1f - ph) * 0.65f), dir - 38f, 76f, false,
                            Offset(body.x - r, body.y - r), Size(r * 2f, r * 2f),
                            style = Stroke(1.6f * ks, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        )
                    }
                }
                drawSpeaker(spot.channel, at, ks, if (playing) c else Color(0xFF59606B), playing, look.surroundShowSpeakerIcons)
            }

            // Edge vignette frames the scene like a lens
            drawRect(
                Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(0.55f)), me, wPx * 0.80f),
                Offset.Zero, Size(wPx, hPx)
            )

            // 360° move: the sound orb, a dashed line back to the listener
            if (move != null) {
                val a = move.angleDeg * PI.toFloat() / 180f
                val floorSpot = floorPoint(sin(a) * move.distance, cos(a) * move.distance)
                val orb = Offset(floorSpot.x, floorSpot.y - 16f * k)
                drawLine(
                    glow.copy(0.7f), me, floorSpot, 1.5f * k + 0.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * k, 4f * k))
                )
                drawOval(Color.Black.copy(0.40f), Offset(floorSpot.x - 11f * k, floorSpot.y - 4f * k), Size(22f * k, 8f * k))
                drawLine(glow.copy(0.35f), floorSpot, orb, 1.2f * k)
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
                        .offset { IntOffset((at.x - 14.dp.toPx()).roundToInt(), (at.y + 3.dp.toPx()).roundToInt()) }
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

// Room view geometry shared by the drawing and the touch mapping.
private const val STAGE_CY = 0.55f
private const val STAGE_RX = 0.36f
private const val STAGE_RY = 0.30f
private const val STAGE_PERSPECTIVE = 0.30f

/** 1 at the listener, smaller towards the screen (fy > 0), larger towards the back of the room (fy < 0). */
private fun stageDepthScale(fy: Float): Float = 1f / (1f + STAGE_PERSPECTIVE * fy)

/** Auto-orbit moves the sound 20 times a second; how long one cycle takes is the Orbit speed slider. */
private const val ORBIT_STEP_S = 0.05f

/**
 * Touch point in the room view -> sound position. Matches the room geometry in [SurroundStageView]
 * (centre at 54% height, ring radii 38.5% of the width / 33.5% of the height). Touching right on the listener
 * keeps the current angle, since "straight ahead of nothing" has no direction.
 */
private fun positionFromTouch(x: Float, y: Float, w: Float, h: Float, currentAngle: Float): Pair<Float, Float> {
    // Undo the floor perspective: screen y = fy * p(fy) with p = 1 / (1 + c * fy), so fy = Y / (1 - c * Y).
    val sy = -(y - h * STAGE_CY) / (h * STAGE_RY)
    val denom = (1f - STAGE_PERSPECTIVE * sy).coerceAtLeast(0.2f)
    val fy = sy / denom
    val dx = (x - w * 0.5f) / (w * STAGE_RX) * (1f + STAGE_PERSPECTIVE * fy)
    val distance = sqrt(dx * dx + fy * fy)
    val angle = if (distance < 0.05f) currentAngle
    else normalizeSurroundAngle(Math.toDegrees(atan2(dx, fy).toDouble()).toFloat())
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
            .background(Brush.verticalGradient(listOf(BgGradTop, BgGradBottom)))
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
            enabled = LocalSurroundControlsEnabled.current,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(0.12f),
                disabledThumbColor = accent.copy(0.35f),
                disabledActiveTrackColor = accent.copy(0.25f),
                disabledInactiveTrackColor = Color.White.copy(0.08f)
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
            enabled = LocalSurroundControlsEnabled.current,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(0.12f),
                disabledThumbColor = accent.copy(0.35f),
                disabledActiveTrackColor = accent.copy(0.25f),
                disabledInactiveTrackColor = Color.White.copy(0.08f)
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
internal fun UpmixLabPanel(
    config: DspConfig,
    accent: Color,
    onEnabled: (Boolean) -> Unit,
    onKnob: (SurroundLabKnob, Float) -> Unit,
    onReset: () -> Unit,
    speakerSetup: @Composable () -> Unit = {}
) {
    val tuning = config.surroundEffectiveTuning()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(BgGradTop, BgGradBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(0.28f), Color.White.copy(0.05f))), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "UPMIX LAB & SPEAKER SETUP", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black,
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
            if (config.surroundTuneEnabled) "Your tuning replaces the ${config.surroundMode.displayName} mode. It stays on when you pick a preset; the preset only sets the speaker levels."
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
                enabled = LocalSurroundControlsEnabled.current,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(0.12f),
                    disabledThumbColor = accent.copy(0.35f),
                    disabledActiveTrackColor = accent.copy(0.25f),
                    disabledInactiveTrackColor = Color.White.copy(0.08f)
                )
            )
            Text(knob.hint, color = Color.White.copy(0.45f), fontSize = 10.sp, lineHeight = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.10f)))
        speakerSetup()
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

// Room extent in floor-plan units, shared by the walls, the floor and the lane lights.
private const val ROOM_HALF_WIDTH = 1.30f
private const val ROOM_FAR = 1.30f
private const val ROOM_NEAR = -1.30f

// Offset is a value class, so it cannot be a vararg; the three and four corner shapes get their own overloads.
private fun cinemaPoly(a: Offset, b: Offset, c: Offset): Path = cinemaPoly(listOf(a, b, c))
private fun cinemaPoly(a: Offset, b: Offset, c: Offset, d: Offset): Path = cinemaPoly(listOf(a, b, c, d))

private fun cinemaPoly(p: List<Offset>): Path = Path().apply {
    moveTo(p[0].x, p[0].y)
    for (i in 1 until p.size) lineTo(p[i].x, p[i].y)
    close()
}

/** Quadratic curve from [p0] to [p1] through control point [c], written with cubicTo so it works on every Compose version. */
private fun Path.curveTo(p0: Offset, c: Offset, p1: Offset) {
    cubicTo(
        p0.x + (c.x - p0.x) * 2f / 3f, p0.y + (c.y - p0.y) * 2f / 3f,
        p1.x + (c.x - p1.x) * 2f / 3f, p1.y + (c.y - p1.y) * 2f / 3f,
        p1.x, p1.y
    )
}

/**
 * Home-cinema room in perspective: coved ceiling lights, acoustic side walls with wash lights, a glossy floor with
 * lane lights, the seating zone ring and a big curved screen that throws light onto the room while it plays.
 */
private fun DrawScope.drawCinemaRoom(
    floorPoint: (Float, Float) -> Offset,
    w: Float, h: Float, cx: Float, cy: Float, rx: Float, k: Float,
    glow: Color, bgInner: Color, level: Float, playing: Boolean, showScreen: Boolean
) {
    val fl = floorPoint(-ROOM_HALF_WIDTH, ROOM_FAR)
    val fr = floorPoint(ROOM_HALF_WIDTH, ROOM_FAR)
    val nl = floorPoint(-ROOM_HALF_WIDTH, ROOM_NEAR)
    val nr = floorPoint(ROOM_HALF_WIDTH, ROOM_NEAR)
    val hz = fl.y
    val wallTop = hz - h * 0.30f * stageDepthScale(ROOM_FAR)
    val ceilN = -h * 0.02f
    val tlO = Offset(-w * 0.18f, ceilN)
    val trO = Offset(w * 1.18f, ceilN)
    val wl = Offset(fl.x, wallTop)
    val wr = Offset(fr.x, wallTop)
    val lightLevel = if (playing) level else 0.2f

    drawRect(Color(0xFF05070B))
    // Ceiling with glowing cove strips that get smaller towards the screen
    drawPath(cinemaPoly(tlO, trO, wr, wl), Brush.verticalGradient(listOf(Color(0xFF06080C), Color(0xFF121A26)), 0f, wallTop.coerceAtLeast(1f)))
    for (i in 0 until 4) {
        val u = i / 3f
        val y = ceilN + (wallTop - ceilN) * (0.18f + 0.2f * u * u + 0.05f * u)
        val half = w * 0.62f * (1f - u * 0.62f)
        drawLine(glow.copy(0.06f), Offset(cx - half, y), Offset(cx + half, y), 10f * k * (1f - u * 0.6f))
        drawLine(glow.copy(0.18f + 0.22f * (1f - u) + 0.1f * lightLevel), Offset(cx - half, y), Offset(cx + half, y), (3.2f - u * 1.8f) * k)
    }
    // Far wall with acoustic panel seams
    drawPath(cinemaPoly(wl, wr, Offset(fr.x, hz), Offset(fl.x, hz)), Brush.verticalGradient(listOf(Color(0xFF121926), Color(0xFF1B2535)), wallTop, hz))
    for (i in 0 until 14) {
        val x = wl.x + (wr.x - wl.x) * i / 14f
        drawLine(Color.White.copy(0.045f), Offset(x, wallTop), Offset(x, hz), 1f)
    }
    // Side walls: ribs converge towards the screen, a wash light runs along each wall
    for (sign in intArrayOf(-1, 1)) {
        val f = if (sign < 0) fl else fr
        val n = if (sign < 0) nl else nr
        val o = if (sign < 0) tlO else trO
        val brush = if (sign < 0) Brush.horizontalGradient(listOf(Color(0xFF07090D), Color(0xFF141C2A)), 0f, w * 0.3f)
        else Brush.horizontalGradient(listOf(Color(0xFF141C2A), Color(0xFF07090D)), w * 0.7f, w)
        drawPath(cinemaPoly(Offset(f.x, wallTop), f, n, o), brush)
        for (i in 1 until 10) {
            val u = i / 10f
            val fz = ROOM_FAR + (ROOM_NEAR - ROOM_FAR) * u * u
            val p0 = floorPoint(sign * ROOM_HALF_WIDTH, fz)
            val yTop = wallTop + (ceilN - wallTop) * minOf(1f, u * u * 1.15f)
            drawLine(
                Color(0xFF78AADC).copy(0.05f + 0.05f * (1f - u)), p0,
                Offset(p0.x + (o.x - f.x) * u * u * 0.9f, yTop), 1f
            )
        }
        drawLine(
            glow.copy(0.22f + 0.15f * lightLevel),
            Offset(f.x, hz - h * 0.09f * stageDepthScale(ROOM_FAR)), Offset(n.x, n.y - h * 0.16f), 2f * k
        )
    }
    // Glossy floor
    val floor = cinemaPoly(fl, fr, nr, nl)
    drawPath(floor, Brush.verticalGradient(listOf(Color(0xFF0D141E), Color(0xFF162233), Color(0xFF0A0F17)), hz, h))
    clipPath(floor) {
        drawRect(
            Brush.verticalGradient(listOf(glow.copy(0.20f + 0.10f * lightLevel), Color.Transparent), hz, hz + h * 0.25f),
            Offset(0f, hz), Size(w, h * 0.25f)
        )
        for (i in -5..5) {
            drawLine(Color.White.copy(0.045f), floorPoint(i * 0.26f, ROOM_FAR), floorPoint(i * 0.26f, ROOM_NEAR), 1f)
        }
        for (j in 0..8) {
            val z = ROOM_FAR + (ROOM_NEAR - ROOM_FAR) * j / 8f
            drawLine(Color.White.copy(0.04f), floorPoint(-ROOM_HALF_WIDTH, z), floorPoint(ROOM_HALF_WIDTH, z), 1f)
        }
    }
    // Lane lights along both edges of the floor
    for (sign in intArrayOf(-1, 1)) for (j in 0 until 16) {
        val z = ROOM_FAR + (ROOM_NEAR - ROOM_FAR) * j / 15f
        val p = floorPoint(sign * (ROOM_HALF_WIDTH - 0.02f), z)
        val sc = stageDepthScale(z)
        drawCircle(glow.copy(0.18f), 8f * k * sc, p)
        drawCircle(glow.copy(0.85f), 2.2f * k * sc, p)
    }
    // Seating zone halo and the ring the speakers stand on
    fun ring(r: Float) = Path().apply {
        for (i in 0..72) {
            val a = 2f * PI.toFloat() * i / 72f
            val o = floorPoint(sin(a) * r, cos(a) * r)
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
        close()
    }
    drawPath(ring(0.62f), Brush.radialGradient(listOf(glow.copy(0.30f + 0.10f * lightLevel), Color.Transparent), Offset(cx, cy), rx * 0.7f))
    drawPath(ring(0.5f), Color.White.copy(0.06f), style = Stroke(1f))
    drawPath(
        ring(1f), glow.copy(0.38f),
        style = Stroke(1.6f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * k, 7f * k)))
    )

    if (!showScreen) return
    // Curved cinema screen at the far wall
    val sc = floorPoint(0f, ROOM_FAR - 0.02f)
    val ps = stageDepthScale(ROOM_FAR)
    val sw = w * 0.56f * ps
    val sh = w * 0.25f * ps
    val top = sc.y - sh
    val bow = 14f * k * 1.2f
    drawRect(
        Brush.radialGradient(listOf(glow.copy(0.30f * (0.5f + lightLevel)), Color.Transparent), Offset(cx, sc.y - sh / 2f), w * 0.55f),
        Offset.Zero, Size(w, hz + h * 0.1f)
    )
    clipPath(floor) {
        drawPath(
            cinemaPoly(Offset(cx - sw * 0.5f, sc.y), Offset(cx + sw * 0.5f, sc.y), floorPoint(0.9f, -0.1f), floorPoint(-0.9f, -0.1f)),
            Brush.verticalGradient(listOf(glow.copy(0.30f * (0.6f + lightLevel)), Color.Transparent), sc.y, sc.y + h * 0.3f)
        )
    }
    val bezel = Path().apply {
        val a = Offset(cx - sw / 2f - 6f * k, top - 6f * k)
        val b = Offset(cx + sw / 2f + 6f * k, top - 6f * k)
        moveTo(a.x, a.y)
        curveTo(a, Offset(cx, a.y + bow), b)
        lineTo(b.x, sc.y + 6f * k)
        curveTo(Offset(b.x, sc.y + 6f * k), Offset(cx, sc.y + 6f * k + bow), Offset(a.x, sc.y + 6f * k))
        close()
    }
    drawPath(bezel, Color(0xFF04060A))
    val picture = Path().apply {
        val a = Offset(cx - sw / 2f, top)
        val b = Offset(cx + sw / 2f, top)
        moveTo(a.x, a.y)
        curveTo(a, Offset(cx, top + bow), b)
        lineTo(b.x, sc.y)
        curveTo(Offset(b.x, sc.y), Offset(cx, sc.y + bow), Offset(a.x, sc.y))
        close()
    }
    val dim = if (playing) 0.55f + 0.45f * level else 0.35f
    drawPath(
        picture,
        Brush.linearGradient(
            listOf(
                Color(0xFF0E5A86).copy(dim), glow.copy(dim), Color(0xFF7A5CFF).copy(dim), Color(0xFF12304F).copy(dim)
            ),
            Offset(cx - sw / 2f, top), Offset(cx + sw / 2f, sc.y)
        )
    )
    drawPath(
        picture,
        Brush.verticalGradient(listOf(Color.White.copy(0.35f), Color.Transparent, Color.Black.copy(0.25f)), top, sc.y)
    )
    drawPath(
        cinemaPoly(Offset(cx - 10f * k, top + sh * 0.38f), Offset(cx - 10f * k, top + sh * 0.62f), Offset(cx + 14f * k, top + sh * 0.5f)),
        Color.White.copy(0.85f)
    )
}

/** Three cinema seats at the listening spot; the listener sits in the middle one. */
private fun DrawScope.drawCinemaSeating(cx: Float, cy: Float, k: Float, glow: Color) {
    fun seat(x: Float, y: Float, s: Float, highlight: Boolean) {
        val w = 34f * k * s
        val h = 30f * k * s
        drawOval(Color.Black.copy(0.5f), Offset(x - w * 0.7f, y - 3f * k * s), Size(w * 1.4f, 12f * k * s))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF2B3650), Color(0xFF141B2A)), y - h, y),
            Offset(x - w / 2f, y - h), Size(w, h * 0.78f), CornerRadius(9f * k * s)
        )
        drawRoundRect(glow.copy(if (highlight) 0.9f else 0.35f), Offset(x - w / 2f, y - h), Size(w, h * 0.78f), CornerRadius(9f * k * s), style = Stroke(1.4f * k * s))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF202A3F), Color(0xFF10151F)), y - h * 0.3f, y),
            Offset(x - w / 2f - 4f * k * s, y - h * 0.42f), Size(w + 8f * k * s, h * 0.42f), CornerRadius(7f * k * s)
        )
        drawRoundRect(glow.copy(if (highlight) 0.9f else 0.30f), Offset(x - w / 2f - 4f * k * s, y - h * 0.42f), Size(w + 8f * k * s, h * 0.42f), CornerRadius(7f * k * s), style = Stroke(1.4f * k * s))
    }
    val sy = cy + 10f * k
    seat(cx - 72f * k, sy, 1f, false)
    seat(cx + 72f * k, sy, 1f, false)
    seat(cx, sy, 1.12f, true)
    // listener: body and head, with a soft glow
    drawCircle(Brush.radialGradient(listOf(glow.copy(0.35f), Color.Transparent), Offset(cx, cy - 30f * k), 26f * k), 26f * k, Offset(cx, cy - 30f * k))
    drawRoundRect(Color(0xFF161D2B), Offset(cx - 11f * k, cy - 24f * k), Size(22f * k, 18f * k), CornerRadius(8f * k))
    drawRoundRect(glow.copy(0.95f), Offset(cx - 11f * k, cy - 24f * k), Size(22f * k, 18f * k), CornerRadius(8f * k), style = Stroke(1.8f * k))
    drawCircle(Color(0xFF161D2B), 8f * k, Offset(cx, cy - 30f * k))
    drawCircle(glow.copy(0.95f), 8f * k, Offset(cx, cy - 30f * k), style = Stroke(1.8f * k))
}

/** Free-standing 3D speaker: floor shadow, front baffle with drivers, lit top and side faces. [base] is where it stands. */
private fun DrawScope.drawSpeaker(
    channel: SurroundChannel, base: Offset, k: Float, color: Color, playing: Boolean, detailed: Boolean
) {
    val sub = channel == SurroundChannel.SW
    val bw = if (sub) 30f * k else 24f * k
    val bh = if (sub) 28f * k else 42f * k
    val d = 10f * k
    drawOval(Color.Black.copy(0.55f), Offset(base.x - bw * 0.75f, base.y - 4f * k), Size(bw * 1.9f, 10f * k))
    if (!detailed) {
        drawCircle(color, 7f * k, Offset(base.x, base.y - 7f * k))
        return
    }
    val left = base.x - bw / 2f
    val top = base.y - bh
    val edge = color.copy(if (playing) 0.95f else 0.65f)
    val side = cinemaPoly(
        Offset(left + bw, top), Offset(left + bw + d, top - d * 0.6f),
        Offset(left + bw + d, base.y - d * 0.6f), Offset(left + bw, base.y)
    )
    drawPath(side, Color(0xFF090C12))
    drawPath(side, edge.copy(0.35f), style = Stroke(1f * k))
    val topFace = cinemaPoly(
        Offset(left, top), Offset(left + d, top - d * 0.6f), Offset(left + bw + d, top - d * 0.6f), Offset(left + bw, top)
    )
    drawPath(topFace, Brush.linearGradient(listOf(color.copy(if (playing) 0.60f else 0.25f), Color.White.copy(0.20f)), Offset(left, top), Offset(left + bw, top - d)))
    drawPath(topFace, edge.copy(0.5f), style = Stroke(1f * k))
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFF2C3547), Color(0xFF181E2A), Color(0xFF0B0E15)), left, left + bw),
        Offset(left, top), Size(bw, bh), CornerRadius(3f * k)
    )
    drawRoundRect(edge, Offset(left, top), Size(bw, bh), CornerRadius(3f * k), style = Stroke(1.5f * k))
    drawLine(Color.White.copy(if (playing) 0.60f else 0.25f), Offset(left + 2f * k, top + 1f * k), Offset(left + bw - 2f * k, top + 1f * k), 1f * k)
    fun driver(center: Offset, radius: Float) {
        drawCircle(Color.Black.copy(0.70f), radius * 1.2f, center)
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(if (playing) 1f else 0.40f), color.copy(if (playing) 0.40f else 0.14f), Color(0xFF0B0E14)),
                center, radius
            ),
            radius, center
        )
        drawCircle(color.copy(0.9f), radius, center, style = Stroke(1.2f * k))
        drawCircle(Color.White.copy(if (playing) 0.40f else 0.12f), radius * 0.28f, Offset(center.x - radius * 0.30f, center.y - radius * 0.34f))
    }
    if (sub) {
        driver(Offset(base.x, top + bh / 2f), bw * 0.34f)
    } else {
        driver(Offset(base.x, top + bh * 0.24f), 3.6f * k)
        driver(Offset(base.x, top + bh * 0.66f), 7.2f * k)
    }
    drawCircle(if (playing) color else Color(0xFF3A4150), 1.3f * k, Offset(left + bw - 3f * k, base.y - 3f * k))
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
    .background(Brush.verticalGradient(listOf(BgPanel.copy(alpha = 0.88f), BgPanelDeep.copy(alpha = 0.92f))))
    .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.07f), Color.Transparent), radius = 700f))
    .border(
        1.dp,
        Brush.linearGradient(listOf(primary.copy(0.55f), primary.copy(0.10f), secondary.copy(0.45f))),
        RoundedCornerShape(radius)
    )

@Composable
internal fun LayoutButton(label: String, icon: ImageVector, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(46.dp)
            .then(if (selected) Modifier.shadow(10.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (selected) accent.copy(0.18f) else BgPanel.copy(0.9f))
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
internal fun PresetChip(name: String, selected: Boolean, enabled: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(38.dp)
            .then(if (selected && enabled) Modifier.shadow(8.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (selected) accent.copy(0.18f) else BgPanel.copy(0.9f))
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
    val controlsOn = LocalSurroundControlsEnabled.current
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
            .pointerInput(controlsOn) {
                if (!controlsOn) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { latestOnChange(0f) },
                    onTap = { latestOnChange(toDb(it.y)) }
                )
            }
            .pointerInput(controlsOn) {
                if (!controlsOn) return@pointerInput
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
        drawRoundRect(BgGradTop, Offset(x - capW / 2, 0f), Size(capW, size.height), CornerRadius(capW / 2))
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
            Brush.verticalGradient(listOf(Color(0xFF6B7380), Color(0xFF3A404B), bgTone(0xFF1E222A)), thumbY - capH / 2, thumbY + capH / 2),
            Offset(x - knobW / 2, thumbY - capH / 2), Size(knobW, capH), CornerRadius(7.dp.toPx())
        )
        drawRoundRect(tint.copy(0.8f), Offset(x - knobW / 2, thumbY - capH / 2), Size(knobW, capH), CornerRadius(7.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawLine(tint, Offset(x - 8.dp.toPx(), thumbY), Offset(x + 8.dp.toPx(), thumbY), 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
