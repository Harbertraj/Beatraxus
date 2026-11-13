package com.beatraxus.app.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Output layout requested on the Studio DSP > 5.1/7.1 screen.
 *
 * [channels] is the PCM channel count handed to the audio output. [AUTO] has no fixed count (0): it is
 * resolved at runtime from what the active output device can accept, see [resolveSurroundOutputChannels].
 */
enum class SurroundLayout(val displayName: String, val channels: Int) {
    STEREO("Stereo", 2),
    FIVE_ONE("5.1", 6),
    SEVEN_ONE("7.1", 8),
    AUTO("Auto", 0)
}

/** Character of the stereo -> multichannel upmix. Per-channel trims are applied on top of every mode. */
enum class SurroundMode(val displayName: String) {
    MOVIE("Movie"),
    MUSIC("Music"),
    GAME("Game"),
    CUSTOM("Custom")
}

/**
 * Matrix parameters behind a [SurroundMode].
 *
 * @property centerAmount  0..1, how much of the mono (L+R) content is steered to the centre speaker.
 * @property surroundAmount 0..1, how much of the side (L-R) content is steered to the surround speakers.
 * @property rearShare     0..1, 7.1 only: share of the surround content that goes to the back pair instead of the sides.
 * @property lfeCrossoverHz low-pass corner of the subwoofer feed.
 * @property surroundDelayMs / [rearDelayMs] ambience delay of the side / back pair.
 */
data class SurroundTuning(
    val centerAmount: Float,
    val surroundAmount: Float,
    val rearShare: Float,
    val lfeCrossoverHz: Float,
    val surroundDelayMs: Float,
    val rearDelayMs: Float
)

fun SurroundMode.tuning(): SurroundTuning = when (this) {
    SurroundMode.MOVIE -> SurroundTuning(0.55f, 0.65f, 0.45f, 120f, 12f, 22f)
    SurroundMode.MUSIC -> SurroundTuning(0.20f, 0.40f, 0.35f, 100f, 14f, 26f)
    SurroundMode.GAME -> SurroundTuning(0.35f, 0.85f, 0.55f, 120f, 8f, 16f)
    SurroundMode.CUSTOM -> SurroundTuning(0.35f, 0.50f, 0.40f, 110f, 12f, 22f)
}

/**
 * The eight speaker positions shown in the room view and in the CHANNEL LEVELS strip.
 * Order is the order of [DspConfig.surroundChannelTrimsDb].
 */
enum class SurroundChannel(val label: String, val fullName: String) {
    L("L", "Front Left"),
    R("R", "Front Right"),
    C("C", "Center"),
    SL("SL", "Surround Left"),
    SR("SR", "Surround Right"),
    BL("BL", "Back Left"),
    BR("BR", "Back Right"),
    SW("SW", "Subwoofer");

    /** Back pair only exists in a 7.1 layout. */
    val sevenOneOnly: Boolean get() = this == BL || this == BR
}

const val SURROUND_TRIM_MIN_DB = -12f
const val SURROUND_TRIM_MAX_DB = 12f

/** L, R, C, SL, SR, BL, BR, SW (dB). Same values the "Default" preset shows. */
val DEFAULT_SURROUND_TRIMS_DB: List<Float> = listOf(0f, 0f, 1f, -1f, -1f, -1.5f, -1.5f, 2f)

data class SurroundPreset(
    val name: String,
    val mode: SurroundMode,
    val trimsDb: List<Float>,
    /** One plain-language line shown under the preset chips. */
    val description: String = ""
)

/** Built-in presets. A user slot ("Custom 1") is stored separately in [DspConfig.surroundCustomTrimsDb]. */
val BUILT_IN_SURROUND_PRESETS: List<SurroundPreset> = listOf(
    SurroundPreset("Default", SurroundMode.MOVIE, DEFAULT_SURROUND_TRIMS_DB, "Balanced for movies and music."),
    SurroundPreset("Cinema", SurroundMode.MOVIE, listOf(0f, 0f, 2.5f, -0.5f, -0.5f, -1f, -1f, 4f), "Clear dialogue and deep bass."),
    SurroundPreset("Concert", SurroundMode.MUSIC, listOf(0f, 0f, -1f, 1.5f, 1.5f, 1f, 1f, 0f), "Airy surrounds, like a live hall."),
    SurroundPreset("Gaming", SurroundMode.GAME, listOf(0f, 0f, 1f, 2f, 2f, 1.5f, 1.5f, 1f), "Strong surrounds for footsteps and direction."),
    SurroundPreset("Voice", SurroundMode.MOVIE, listOf(-1.5f, -1.5f, 4f, -4f, -4f, -5f, -5f, -3f), "Dialogue up front, surrounds kept quiet."),
    SurroundPreset("Night", SurroundMode.MOVIE, listOf(0f, 0f, 1.5f, -3f, -3f, -4f, -4f, -6f), "Softer surrounds and bass for late hours."),
    SurroundPreset("Wide", SurroundMode.MUSIC, listOf(0f, 0f, -2f, 3f, 3f, 2f, 2f, 1f), "Extra wide stage, centre pulled back.")
)

const val SURROUND_CUSTOM_PRESET_NAME = "Custom 1"

/**
 * The line shown under the preset chips: what the active preset does, or why nothing is highlighted.
 * [activePreset] is [DspConfig.surroundActivePreset]; an empty name means the levels were moved by hand.
 */
fun surroundPresetDescription(activePreset: String): String = when {
    activePreset.isEmpty() -> "Your own mix. Tap Save as Custom 1 to keep it."
    activePreset == SURROUND_CUSTOM_PRESET_NAME -> "Your saved mix."
    else -> BUILT_IN_SURROUND_PRESETS.firstOrNull { it.name == activePreset }?.description.orEmpty()
}

/** Always exactly eight finite values inside the allowed range, whatever was stored. */
fun sanitizeSurroundTrims(trims: List<Float>?): List<Float> {
    val source = trims ?: DEFAULT_SURROUND_TRIMS_DB
    return List(DEFAULT_SURROUND_TRIMS_DB.size) { i ->
        val v = source.getOrNull(i) ?: DEFAULT_SURROUND_TRIMS_DB[i]
        if (v.isNaN()) DEFAULT_SURROUND_TRIMS_DB[i] else v.coerceIn(SURROUND_TRIM_MIN_DB, SURROUND_TRIM_MAX_DB)
    }
}

/**
 * PCM channel count the audio output must be opened with for the current settings.
 *
 * Returns [sourceChannels] unchanged (= behaviour exactly as before the simulator existed) unless every
 * condition for an upmix holds. This single function is used by both the output set-up and the DSP pipeline,
 * so the two can never disagree about the channel count.
 *
 * Surround is deliberately skipped for: the master toggle being off, A/B "bypass all", Bit-Perfect, DSD over PCM
 * (DoP), and sources that are already multichannel (a real 5.1 / 7.1 soundtrack keeps its own discrete channels,
 * see [SurroundStage.DISCRETE]). Video soundtracks that are routed through the Audio Engine take part like any
 * other source: a stereo track is upmixed, a 5.1 track is left alone.
 *
 * @param deviceMaxChannels widest layout the output device reports; only used for [SurroundLayout.AUTO].
 */
fun resolveSurroundOutputChannels(
    config: DspConfig,
    sourceChannels: Int,
    isDoP: Boolean,
    deviceMaxChannels: Int
): Int {
    if (!config.surroundEnabled || config.bypassAll || config.bitPerfectEnabled) return sourceChannels
    if (isDoP) return sourceChannels
    if (sourceChannels !in 1..2) return sourceChannels
    val target = when (config.surroundLayout) {
        SurroundLayout.STEREO -> 2
        SurroundLayout.FIVE_ONE -> 6
        SurroundLayout.SEVEN_ONE -> 8
        SurroundLayout.AUTO -> when {
            deviceMaxChannels >= 8 -> 8
            deviceMaxChannels >= 6 -> 6
            else -> 2
        }
    }
    return if (target > 2) target else sourceChannels
}

/** What the surround stage does with one stream. */
enum class SurroundStage {
    /** Not in the signal path: the stream leaves the pipeline exactly as the rest of the chain produced it. */
    OFF,

    /** Stereo / mono source upmixed to 5.1 / 7.1 by [SurroundUpmixer]. */
    UPMIX,

    /**
     * The source already is a real 5.1 / 7.1 mix (e.g. an AC3 / E-AC3 / DTS / TrueHD soundtrack decoded with all its
     * channels). Its discrete channels are kept as they are, nothing is re-synthesised. Only the per-speaker trims
     * apply, relative to the "Default" preset, so untouched trims leave the audio bit-for-bit unchanged.
     */
    DISCRETE
}

/**
 * Decides the [SurroundStage] for one stream.
 *
 * @param outputChannels channel count the audio output is really open with right now.
 */
fun resolveSurroundStage(
    config: DspConfig,
    sourceChannels: Int,
    isDoP: Boolean,
    outputChannels: Int
): SurroundStage {
    if (!config.surroundEnabled || config.bypassAll || config.bitPerfectEnabled || isDoP) return SurroundStage.OFF
    return when (sourceChannels) {
        1, 2 -> if (outputChannels > 2) SurroundStage.UPMIX else SurroundStage.OFF
        6, 8 -> if (outputChannels == sourceChannels) SurroundStage.DISCRETE else SurroundStage.OFF
        else -> SurroundStage.OFF
    }
}

/**
 * Index into the UI trim list (L R C SL SR BL BR SW) for every interleaved position of a discrete stream.
 * Position order follows the Android / FFmpeg channel masks: 5.1 = FL FR FC LFE SL SR, 7.1 = FL FR FC LFE BL BR SL SR.
 * Null for any other channel count.
 */
fun discreteChannelTrimIndex(channels: Int): IntArray? = when (channels) {
    6 -> intArrayOf(0, 1, 2, 7, 3, 4)
    8 -> intArrayOf(0, 1, 2, 7, 5, 6, 3, 4)
    else -> null
}

/**
 * Settings used while a video soundtrack runs through the "5.1 engine" (video player toggle).
 *
 * That route is the plain video-audio path plus the surround simulator and nothing else: every effect of the
 * Studio DSP chain (EQ, tone, spatial, reverb, limiter, ...) is reset to its neutral default, while everything that
 * describes how audio reaches the device (output mode, resampler, sample format, DVC, USB, buffers, dither) is kept so
 * the output still behaves the way the user configured it. The surround simulator is always on for this route, the
 * layout (Stereo / 5.1 / 7.1 / Auto) is the one chosen in the video player, and Bit-Perfect / A/B bypass do not
 * switch it off.
 */
fun DspConfig.toSurroundEngineConfig(): DspConfig = DspConfig(
    outputMode = outputMode,
    highQualityResampler = highQualityResampler,
    soxrQuality = soxrQuality,
    float64Enabled = float64Enabled,
    resamplerMode = resamplerMode,
    resamplerCutoffRatio = resamplerCutoffRatio,
    sampleFormat = sampleFormat,
    dvcEnabled = dvcEnabled,
    dvcBluetoothEnabled = dvcBluetoothEnabled,
    dvcMode = dvcMode,
    dvcLevel = dvcLevel,
    compensateDvcVolumeEnabled = compensateDvcVolumeEnabled,
    dvcCompensationDb = dvcCompensationDb,
    usbExclusiveEnabled = usbExclusiveEnabled,
    outputBufferMs = outputBufferMs,
    outputBufferCount = outputBufferCount,
    postFadeBufferMs = postFadeBufferMs,
    mmapRequestedBufferSizeFrames = mmapRequestedBufferSizeFrames,
    ditherEnabled = ditherEnabled,
    ditherType = ditherType,
    hardwareVolumeEnabled = hardwareVolumeEnabled,
    customUsbDriverEnabled = customUsbDriverEnabled,
    surroundEnabled = true,
    // The video player lets the user pick Stereo / 5.1 / 7.1 / Auto. For a video soundtrack "Auto" follows the track:
    // a real 5.1 / 7.1 mix keeps its own channels and a stereo track stays stereo (see [resolveSurroundOutputChannels]),
    // so it resolves to Stereo here (no upmix), and Stereo itself is honoured instead of being turned into 5.1.
    surroundLayout = if (surroundLayout == SurroundLayout.AUTO) SurroundLayout.STEREO else surroundLayout,
    surroundMode = surroundMode,
    surroundChannelTrimsDb = surroundChannelTrimsDb,
    surroundActivePreset = surroundActivePreset,
    surroundCustomTrimsDb = surroundCustomTrimsDb,
    surroundCustomMode = surroundCustomMode,
    surroundMoveEnabled = surroundMoveEnabled,
    surroundPositionAngleDeg = surroundPositionAngleDeg,
    surroundPositionDistance = surroundPositionDistance,
    surroundTuneEnabled = surroundTuneEnabled,
    surroundTune = surroundTune
)

// ── 360° move: place the sound anywhere around the listener ────────────────────────────────────────────────

const val SURROUND_POSITION_DEFAULT_ANGLE_DEG = 0f
const val SURROUND_POSITION_DEFAULT_DISTANCE = 0.8f
const val SURROUND_POSITION_MIN_DISTANCE = 0.15f
const val SURROUND_POSITION_MAX_DISTANCE = 1f

/** Highest boost / deepest cut the position adds on top of the channel levels (dB). */
const val SURROUND_POSITION_MAX_BOOST_DB = 6f
const val SURROUND_POSITION_MAX_CUT_DB = -12f

/**
 * Speaker angles in degrees clockwise from straight ahead, in UI order L R C SL SR BL BR SW.
 * Same angles the room view draws. The subwoofer has no direction and is never moved by the position.
 */
val SURROUND_SPEAKER_ANGLES_DEG: List<Float> = listOf(-32f, 32f, 0f, -90f, 90f, -148f, 148f, 0f)

/** Wraps any angle into 0..360. NaN becomes straight ahead. */
fun normalizeSurroundAngle(angleDeg: Float): Float {
    if (angleDeg.isNaN() || angleDeg.isInfinite()) return SURROUND_POSITION_DEFAULT_ANGLE_DEG
    val a = angleDeg % 360f
    return if (a < 0f) a + 360f else a
}

fun sanitizeSurroundDistance(distance: Float): Float =
    if (distance.isNaN()) SURROUND_POSITION_DEFAULT_DISTANCE
    else distance.coerceIn(SURROUND_POSITION_MIN_DISTANCE, SURROUND_POSITION_MAX_DISTANCE)

/** Smallest angle between two directions, 0..180. */
internal fun surroundAngleGap(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/**
 * Per-speaker level change (dB, UI order L R C SL SR BL BR SW) that places the sound at [angleDeg] / [distance].
 *
 * A speaker is lifted the closer it stands to the chosen direction and lowered the further away it is. A far
 * position (distance 1) is focused: speakers in that direction are boosted and the opposite side is cut. A close
 * position (towards 0.15) spreads the sound evenly around the listener with only a gentle lean. Boost is capped at
 * [SURROUND_POSITION_MAX_BOOST_DB] so the sum cannot run into the limiter, the cut at [SURROUND_POSITION_MAX_CUT_DB].
 * The subwoofer stays at 0 dB.
 */
fun surroundPositionGainsDb(angleDeg: Float, distance: Float): List<Float> {
    val angle = normalizeSurroundAngle(angleDeg)
    val d = sanitizeSurroundDistance(distance)
    val focus = 0.3f + 0.7f * d
    val spread = 55f + (1f - d) * 40f
    return List(SURROUND_SPEAKER_ANGLES_DEG.size) { i ->
        if (i == SW_INDEX) {
            0f
        } else {
            val gap = surroundAngleGap(angle, SURROUND_SPEAKER_ANGLES_DEG[i]) / spread
            val w = exp(-gap * gap)
            val level = 0.5f * (1f - focus) + w * focus
            val db = ((level - 0.5f) * 24f * 2f).roundToInt() / 2f
            db.coerceIn(SURROUND_POSITION_MAX_CUT_DB, SURROUND_POSITION_MAX_BOOST_DB)
        }
    }
}

private const val SW_INDEX = 7

/** A named spot for the sound, one tap in 360° move. */
data class SurroundPositionPreset(val name: String, val angleDeg: Float, val distance: Float)

val BUILT_IN_SURROUND_POSITIONS: List<SurroundPositionPreset> = listOf(
    SurroundPositionPreset("Front stage", 0f, 0.8f),
    SurroundPositionPreset("Front right", 45f, 0.9f),
    SurroundPositionPreset("Right side", 90f, 1f),
    SurroundPositionPreset("Behind you", 180f, 1f),
    SurroundPositionPreset("Left side", 270f, 1f),
    SurroundPositionPreset("Front left", 315f, 0.9f),
    SurroundPositionPreset("All around", 0f, SURROUND_POSITION_MIN_DISTANCE)
)

/** Moves the sound to a built-in spot; an unknown name changes nothing. */
fun DspConfig.withSurroundPositionPreset(name: String): DspConfig {
    val spot = BUILT_IN_SURROUND_POSITIONS.firstOrNull { it.name == name } ?: return this
    return withSurroundPosition(spot.angleDeg, spot.distance)
}

/** Name of the built-in spot the sound is on right now (within 3° and 5%), or null when it sits somewhere else. */
fun DspConfig.activeSurroundPositionPreset(): String? =
    BUILT_IN_SURROUND_POSITIONS.firstOrNull {
        surroundAngleGap(surroundPositionAngleDeg, it.angleDeg) <= 3f &&
            abs(surroundPositionDistance - it.distance) <= 0.05f
    }?.name

/**
 * Position offsets for a source that already is a real 5.1 / 7.1 mix (the discrete trimmer can only change levels,
 * it cannot re-route sound). null while 360° move is off, so the sound stays exactly as before.
 */
fun DspConfig.surroundPositionOffsetsDb(): List<Float>? =
    if (surroundMoveEnabled) surroundPositionGainsDb(surroundPositionAngleDeg, surroundPositionDistance) else null

// ── 360° move for stereo sources: the whole song is panned around the room at constant loudness ────────────

/**
 * Where the left and right halves of the song sit, relative to the chosen direction, while the sound is moved.
 * +-30 degrees is the classic stereo triangle, so "straight ahead" puts them on the front L / R speakers.
 */
const val SURROUND_MOVE_IMAGE_HALF_WIDTH_DEG = 30f

/**
 * How the upmixer routes a stereo song while 360° move is on.
 *
 * Every speaker gets a share of the song's left channel ([left]) and of its right channel ([right]); both lists are in
 * UI order L R C SL SR BL BR SW, with 0 for C and SW (the centre is [centre], the subwoofer is not moved).
 * Each list is constant power: its squares add up to 1, so the song is equally loud at every angle and distance.
 *
 * @property centre 0..1, how much of the centre (dialogue) feed stays on the centre speaker: 1 straight ahead and
 *                  focused, 0 anywhere else.
 */
class SurroundMoveMix(val left: List<Float>, val right: List<Float>, val centre: Float)

/** Ring of speakers the song can be panned between, clockwise from straight ahead: UI index and angle. */
private val MOVE_RING_71 = listOf(1 to 32f, 4 to 90f, 6 to 148f, 5 to 212f, 3 to 270f, 0 to 328f) // R SR BR BL SL L
private val MOVE_RING_51 = listOf(1 to 32f, 4 to 110f, 3 to 250f, 0 to 328f) // R SR SL L

/** Constant-power pan of one source at [angleDeg] between the two ring speakers that surround it. */
private fun panOnRing(ring: List<Pair<Int, Float>>, angleDeg: Float): FloatArray {
    val gains = FloatArray(SURROUND_SPEAKER_ANGLES_DEG.size)
    val a = normalizeSurroundAngle(angleDeg)
    for (k in ring.indices) {
        val (fromIdx, fromAngle) = ring[k]
        val (toIdx, toAngleRaw) = ring[(k + 1) % ring.size]
        val toAngle = if (toAngleRaw <= fromAngle) toAngleRaw + 360f else toAngleRaw
        val x = if (a < fromAngle) a + 360f else a
        if (x >= fromAngle && x <= toAngle) {
            val t = ((x - fromAngle) / (toAngle - fromAngle)).coerceIn(0f, 1f)
            val theta = t * (Math.PI.toFloat() / 2f)
            gains[fromIdx] = kotlin.math.cos(theta)
            gains[toIdx] = kotlin.math.sin(theta)
            return gains
        }
    }
    return gains
}

/**
 * Routing for [angleDeg] / [distance]. Distance is the whole slider range, no dead zone: at 0.15 the song surrounds
 * the listener evenly, at 1 it is placed exactly at the angle, and everything in between blends the two smoothly.
 */
fun surroundMoveMix(angleDeg: Float, distance: Float, sevenOne: Boolean): SurroundMoveMix {
    val angle = normalizeSurroundAngle(angleDeg)
    val d = sanitizeSurroundDistance(distance)
    val focus = ((d - SURROUND_POSITION_MIN_DISTANCE) / (SURROUND_POSITION_MAX_DISTANCE - SURROUND_POSITION_MIN_DISTANCE))
        .coerceIn(0f, 1f)
    val ring = if (sevenOne) MOVE_RING_71 else MOVE_RING_51
    val even = 1f / ring.size

    fun half(offsetDeg: Float): List<Float> {
        val pan = panOnRing(ring, angle + offsetDeg)
        return List(pan.size) { i ->
            if (ring.none { it.first == i }) 0f
            else kotlin.math.sqrt(focus * pan[i] * pan[i] + (1f - focus) * even)
        }
    }

    val front = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat().coerceAtLeast(0f)
    return SurroundMoveMix(
        left = half(-SURROUND_MOVE_IMAGE_HALF_WIDTH_DEG),
        right = half(SURROUND_MOVE_IMAGE_HALF_WIDTH_DEG),
        centre = front * focus
    )
}

/** 360° routing for the audio engine; null while 360° move is off. */
fun DspConfig.surroundMoveMixOrNull(sevenOne: Boolean): SurroundMoveMix? =
    if (surroundMoveEnabled) surroundMoveMix(surroundPositionAngleDeg, surroundPositionDistance, sevenOne) else null

/**
 * What the room view shows per speaker (dB, UI order L R C SL SR BL BR SW) while the song is moved: the level of
 * every speaker compared with plain stereo, for content that is different on the left and right. Floors at -30.
 */
fun surroundMoveLevelsDb(angleDeg: Float, distance: Float, sevenOne: Boolean): List<Float> {
    val m = surroundMoveMix(angleDeg, distance, sevenOne)
    return List(SURROUND_SPEAKER_ANGLES_DEG.size) { i ->
        when (i) {
            SW_INDEX -> 0f
            2 -> if (m.centre < 0.01f) -30f else (10f * kotlin.math.log10(m.centre * m.centre)).coerceIn(-30f, 0f)
            else -> {
                val p = m.left[i] * m.left[i] + m.right[i] * m.right[i]
                if (p < 0.001f) -30f else (10f * kotlin.math.log10(p)).coerceIn(-30f, 6f)
            }
        }
    }
}

// ── Pure settings edits (shared by every screen that edits the surround settings) ───────────────────────────

/** One speaker's level trim in dB (index = [SurroundChannel] ordinal). Moving a slider leaves the preset unsaved. */
fun DspConfig.withSurroundTrim(index: Int, db: Float): DspConfig {
    if (index !in DEFAULT_SURROUND_TRIMS_DB.indices) return this
    val trims = sanitizeSurroundTrims(surroundChannelTrimsDb).toMutableList()
    trims[index] = db.coerceIn(SURROUND_TRIM_MIN_DB, SURROUND_TRIM_MAX_DB)
    return copy(surroundChannelTrimsDb = trims, surroundActivePreset = "")
}

/** Turns 360° move on or off. The remembered position is kept either way. */
fun DspConfig.withSurroundMove(enabled: Boolean): DspConfig = copy(surroundMoveEnabled = enabled)

/** Moves the sound. Angle is wrapped into 0..360 and distance clamped to the allowed range. */
fun DspConfig.withSurroundPosition(angleDeg: Float, distance: Float): DspConfig = copy(
    surroundPositionAngleDeg = normalizeSurroundAngle(angleDeg),
    surroundPositionDistance = sanitizeSurroundDistance(distance)
)

fun DspConfig.withSurroundPositionReset(): DspConfig = copy(
    surroundPositionAngleDeg = SURROUND_POSITION_DEFAULT_ANGLE_DEG,
    surroundPositionDistance = SURROUND_POSITION_MIN_DISTANCE
)

fun DspConfig.withSurroundTrimsReset(): DspConfig =
    copy(surroundChannelTrimsDb = DEFAULT_SURROUND_TRIMS_DB, surroundActivePreset = "Default")

/** Applies a built-in preset or the user slot ([SURROUND_CUSTOM_PRESET_NAME]); an unknown / empty name changes nothing. */
fun DspConfig.withSurroundPreset(name: String): DspConfig {
    if (name == SURROUND_CUSTOM_PRESET_NAME) {
        val saved = surroundCustomTrimsDb ?: return this
        return copy(
            surroundChannelTrimsDb = saved, surroundMode = surroundCustomMode, surroundActivePreset = name,
            surroundTuneEnabled = false
        )
    }
    val preset = BUILT_IN_SURROUND_PRESETS.firstOrNull { it.name == name } ?: return this
    return copy(
        surroundChannelTrimsDb = preset.trimsDb, surroundMode = preset.mode, surroundActivePreset = name,
        surroundTuneEnabled = false
    )
}
