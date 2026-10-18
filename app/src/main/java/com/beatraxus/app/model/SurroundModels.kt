package com.beatraxus.app.model

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
    val trimsDb: List<Float>
)

/** Built-in presets. A user slot ("Custom 1") is stored separately in [DspConfig.surroundCustomTrimsDb]. */
val BUILT_IN_SURROUND_PRESETS: List<SurroundPreset> = listOf(
    SurroundPreset("Default", SurroundMode.MOVIE, DEFAULT_SURROUND_TRIMS_DB),
    SurroundPreset("Cinema", SurroundMode.MOVIE, listOf(0f, 0f, 2.5f, -0.5f, -0.5f, -1f, -1f, 4f)),
    SurroundPreset("Concert", SurroundMode.MUSIC, listOf(0f, 0f, -1f, 1.5f, 1.5f, 1f, 1f, 0f)),
    SurroundPreset("Gaming", SurroundMode.GAME, listOf(0f, 0f, 1f, 2f, 2f, 1.5f, 1.5f, 1f)),
    SurroundPreset("Voice", SurroundMode.MOVIE, listOf(-1.5f, -1.5f, 4f, -4f, -4f, -5f, -5f, -3f)),
    SurroundPreset("Night", SurroundMode.MOVIE, listOf(0f, 0f, 1.5f, -3f, -3f, -4f, -4f, -6f)),
    SurroundPreset("Wide", SurroundMode.MUSIC, listOf(0f, 0f, -2f, 3f, 3f, 2f, 2f, 1f))
)

const val SURROUND_CUSTOM_PRESET_NAME = "Custom 1"

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
 * (DoP), video soundtracks (they keep their own channel layout), and sources that are already multichannel.
 *
 * @param deviceMaxChannels widest layout the output device reports; only used for [SurroundLayout.AUTO].
 */
fun resolveSurroundOutputChannels(
    config: DspConfig,
    sourceChannels: Int,
    isDoP: Boolean,
    isVideoRoute: Boolean,
    deviceMaxChannels: Int
): Int {
    if (!config.surroundEnabled || config.bypassAll || config.bitPerfectEnabled) return sourceChannels
    if (isDoP || isVideoRoute) return sourceChannels
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
