package com.beatraxus.app.model

import kotlin.math.roundToInt

/**
 * Studio DSP > Surround extras: headphone (binaural) surround, speaker calibration, bass management, smart steering,
 * extra preset slots with import / export, and the Master-page audio upscaler.
 *
 * Everything here is pure settings logic. Every new switch is OFF by default and, while off, the audio path is
 * exactly what it was before these extras existed.
 */

// ── Headphone (binaural) surround ────────────────────────────────────────────────────────────────────────────

/**
 * Number of virtual speakers (6 = 5.1, 8 = 7.1) the stereo stream is upmixed to and then folded back to two ears with
 * a head model, or 0 when headphone surround is not in the signal path.
 *
 * Headphone surround only exists for a stereo source on a stereo output: the output keeps its own channel count, the
 * 5.1 / 7.1 stage runs inside the pipeline. Layout "Stereo" has nothing to virtualise; "Auto" uses 7.1 (it has the
 * most direction cues). Bit-Perfect, A/B bypass and DSD (DoP) switch it off like the normal upmix.
 */
fun resolveSurroundBinauralChannels(
    config: DspConfig,
    sourceChannels: Int,
    isDoP: Boolean,
    outputChannels: Int
): Int {
    if (!config.surroundEnabled || !config.surroundHeadphoneMode) return 0
    if (config.bypassAll || config.bitPerfectEnabled || isDoP) return 0
    if (sourceChannels != 2 || outputChannels != 2) return 0
    return when (config.surroundLayout) {
        SurroundLayout.STEREO -> 0
        SurroundLayout.FIVE_ONE -> 6
        SurroundLayout.SEVEN_ONE, SurroundLayout.AUTO -> 8
    }
}

fun DspConfig.withSurroundHeadphoneMode(enabled: Boolean): DspConfig = copy(surroundHeadphoneMode = enabled)

// ── Smart steering (adaptive upmix) ──────────────────────────────────────────────────────────────────────────

const val SURROUND_SMART_DEFAULT_AMOUNT = 0.6f

fun sanitizeSmartSteeringAmount(v: Float): Float =
    if (v.isNaN() || v.isInfinite()) SURROUND_SMART_DEFAULT_AMOUNT else v.coerceIn(0f, 1f)

fun DspConfig.withSurroundSmartSteering(enabled: Boolean): DspConfig = copy(surroundSmartSteering = enabled)

fun DspConfig.withSurroundSmartSteeringAmount(amount: Float): DspConfig =
    copy(surroundSmartSteering = true, surroundSmartSteeringAmount = sanitizeSmartSteeringAmount(amount))

/** Steering strength the engine uses: 0 while the switch is off. */
fun DspConfig.surroundSmartSteeringStrength(): Float =
    if (surroundSmartSteering) sanitizeSmartSteeringAmount(surroundSmartSteeringAmount) else 0f

// ── Speaker calibration: distance -> delay, bass management, test tone ───────────────────────────────────────

const val SURROUND_DISTANCE_MIN_M = 0.5f
const val SURROUND_DISTANCE_MAX_M = 10f
const val SURROUND_DISTANCE_DEFAULT_M = 2.5f
private const val SPEED_OF_SOUND_M_S = 343f
private const val MAX_CALIBRATION_DELAY_MS = 40f

/** L, R, C, SL, SR, BL, BR, SW. All equal = no delay anywhere. */
val DEFAULT_SURROUND_DISTANCES_M: List<Float> = List(8) { SURROUND_DISTANCE_DEFAULT_M }

/** Always eight finite values inside the allowed range, snapped to 0.1 m, whatever was stored. */
fun sanitizeSurroundDistances(distances: List<Float>?): List<Float> = List(DEFAULT_SURROUND_DISTANCES_M.size) { i ->
    val v = distances?.getOrNull(i)
    if (v == null || v.isNaN() || v.isInfinite()) SURROUND_DISTANCE_DEFAULT_M
    else (v.coerceIn(SURROUND_DISTANCE_MIN_M, SURROUND_DISTANCE_MAX_M) * 10f).roundToInt() / 10f
}

/**
 * Delay (ms, UI order L R C SL SR BL BR SW) that makes every speaker's sound reach the listener together: the farthest
 * speaker gets no delay, nearer ones wait for the difference. Back speakers only count in a 7.1 layout.
 */
fun surroundSpeakerDelaysMs(distances: List<Float>?, sevenOne: Boolean): FloatArray {
    val d = sanitizeSurroundDistances(distances)
    var farthest = 0f
    for (i in d.indices) {
        if (!sevenOne && (i == 5 || i == 6)) continue
        if (d[i] > farthest) farthest = d[i]
    }
    return FloatArray(d.size) { i ->
        if (!sevenOne && (i == 5 || i == 6)) 0f
        else ((farthest - d[i]) / SPEED_OF_SOUND_M_S * 1000f).coerceIn(0f, MAX_CALIBRATION_DELAY_MS)
    }
}

/** Delays for the engine; null while distance calibration is off or every speaker is equally far (nothing to do). */
fun DspConfig.surroundCalibrationDelaysMsOrNull(sevenOne: Boolean): FloatArray? {
    if (!surroundCalibrationEnabled) return null
    val delays = surroundSpeakerDelaysMs(surroundSpeakerDistancesM, sevenOne)
    return if (delays.all { it < 0.01f }) null else delays
}

fun DspConfig.withSurroundCalibration(enabled: Boolean): DspConfig = copy(surroundCalibrationEnabled = enabled)

/** One speaker's distance in metres (index = [SurroundChannel] ordinal). Moving a slider switches calibration on. */
fun DspConfig.withSurroundDistance(index: Int, metres: Float): DspConfig {
    if (index !in DEFAULT_SURROUND_DISTANCES_M.indices) return this
    val list = sanitizeSurroundDistances(surroundSpeakerDistancesM).toMutableList()
    list[index] = sanitizeSurroundDistances(listOf(metres)).first()
    return copy(surroundSpeakerDistancesM = list, surroundCalibrationEnabled = true)
}

fun DspConfig.withSurroundDistancesReset(): DspConfig =
    copy(surroundSpeakerDistancesM = DEFAULT_SURROUND_DISTANCES_M)

fun DspConfig.withSurroundBassManagement(enabled: Boolean): DspConfig = copy(surroundBassManagement = enabled)

/** Speaker whose test noise plays (index = [SurroundChannel] ordinal), or -1 for none. Not saved. */
fun DspConfig.withSurroundTestChannel(index: Int): DspConfig =
    copy(surroundTestChannel = if (index in 0 until DEFAULT_SURROUND_DISTANCES_M.size) index else -1)

/** Plain stereo as recorded (front pair only) while the A/B button is held. Not saved. */
fun DspConfig.withSurroundHoldOriginal(hold: Boolean): DspConfig = copy(surroundHoldOriginal = hold)

fun serializeSurroundDistances(distances: List<Float>?): String =
    sanitizeSurroundDistances(distances).joinToString(",")

fun deserializeSurroundDistances(raw: String?): List<Float>? {
    if (raw.isNullOrBlank()) return null
    val v = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
    return if (v.size == DEFAULT_SURROUND_DISTANCES_M.size) sanitizeSurroundDistances(v) else null
}

// ── Extra preset slots (Custom 2..4) and preset codes ────────────────────────────────────────────────────────

/** Names of the user slots. "Custom 1" keeps living in [DspConfig.surroundCustomTrimsDb] as before. */
val SURROUND_EXTRA_SLOT_NAMES: List<String> = listOf("Custom 2", "Custom 3", "Custom 4")

/** Every user slot name, in screen order. */
val SURROUND_ALL_SLOT_NAMES: List<String> = listOf(SURROUND_CUSTOM_PRESET_NAME) + SURROUND_EXTRA_SLOT_NAMES

data class SurroundSlotPreset(val name: String, val mode: SurroundMode, val trimsDb: List<Float>)

/** True when the user slot [name] holds a saved mix. */
fun DspConfig.surroundSlotFilled(name: String): Boolean = when (name) {
    SURROUND_CUSTOM_PRESET_NAME -> surroundCustomTrimsDb != null
    else -> surroundSlotPresets.any { it.name == name }
}

/** Stores the current trims + mode in user slot [name] ("Custom 1".."Custom 4"); any other name changes nothing. */
fun DspConfig.withSurroundSlotSaved(name: String): DspConfig {
    val trims = sanitizeSurroundTrims(surroundChannelTrimsDb)
    return when (name) {
        SURROUND_CUSTOM_PRESET_NAME -> copy(
            surroundCustomTrimsDb = trims, surroundCustomMode = surroundMode, surroundActivePreset = name
        )
        in SURROUND_EXTRA_SLOT_NAMES -> copy(
            surroundSlotPresets = surroundSlotPresets.filterNot { it.name == name } +
                SurroundSlotPreset(name, surroundMode, trims),
            surroundActivePreset = name
        )
        else -> this
    }
}

fun serializeSurroundSlots(slots: List<SurroundSlotPreset>): String =
    slots.filter { it.name in SURROUND_EXTRA_SLOT_NAMES }.joinToString(";") { s ->
        s.name + "|" + s.mode.name + "|" + sanitizeSurroundTrims(s.trimsDb).joinToString(",")
    }

fun deserializeSurroundSlots(raw: String?): List<SurroundSlotPreset> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';').mapNotNull { part ->
        val f = part.split('|')
        if (f.size != 3 || f[0] !in SURROUND_EXTRA_SLOT_NAMES) return@mapNotNull null
        val mode = SurroundMode.entries.firstOrNull { it.name == f[1] } ?: return@mapNotNull null
        val t = f[2].split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (t.size != DEFAULT_SURROUND_TRIMS_DB.size) return@mapNotNull null
        SurroundSlotPreset(f[0], mode, sanitizeSurroundTrims(t))
    }.distinctBy { it.name }
}

private const val PRESET_CODE_PREFIX = "BTXS1"

/** One line of text that carries the current mix (mode + eight speaker levels), for sharing or backing up. */
fun surroundPresetCode(config: DspConfig): String =
    PRESET_CODE_PREFIX + "|" + config.surroundMode.name + "|" +
        sanitizeSurroundTrims(config.surroundChannelTrimsDb).joinToString(",")

/** Reads a code made by [surroundPresetCode]; null when the text is anything else. */
fun parseSurroundPresetCode(text: String?): Pair<SurroundMode, List<Float>>? {
    val f = text?.trim()?.split('|') ?: return null
    if (f.size != 3 || f[0] != PRESET_CODE_PREFIX) return null
    val mode = SurroundMode.entries.firstOrNull { it.name == f[1] } ?: return null
    val t = f[2].split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (t.size != DEFAULT_SURROUND_TRIMS_DB.size || t.any { it.isNaN() || it.isInfinite() }) return null
    return mode to sanitizeSurroundTrims(t)
}

/** Applies a code from the clipboard as a hand-made mix. Returns this unchanged when the text is not a valid code. */
fun DspConfig.withSurroundPresetCode(text: String?): DspConfig {
    val (mode, trims) = parseSurroundPresetCode(text) ?: return this
    return copy(
        surroundMode = mode, surroundChannelTrimsDb = trims, surroundActivePreset = ""
    )
}

// ── Audio upscaler (Studio DSP > Master) ─────────────────────────────────────────────────────────────────────

const val AUDIO_UPSCALER_CUTOFF_MIN_HZ = 8_000f
const val AUDIO_UPSCALER_CUTOFF_MAX_HZ = 20_000f
const val AUDIO_UPSCALER_CUTOFF_DEFAULT_HZ = 16_000f
const val AUDIO_UPSCALER_AMOUNT_DEFAULT = 0.5f

fun sanitizeUpscalerAmount(v: Float): Float =
    if (v.isNaN() || v.isInfinite()) AUDIO_UPSCALER_AMOUNT_DEFAULT else v.coerceIn(0f, 1f)

fun sanitizeUpscalerCutoff(hz: Float): Float =
    if (hz.isNaN() || hz.isInfinite()) AUDIO_UPSCALER_CUTOFF_DEFAULT_HZ
    else hz.coerceIn(AUDIO_UPSCALER_CUTOFF_MIN_HZ, AUDIO_UPSCALER_CUTOFF_MAX_HZ)

fun DspConfig.withAudioUpscaler(enabled: Boolean): DspConfig = copy(audioUpscalerEnabled = enabled)

fun DspConfig.withAudioUpscalerAmount(amount: Float): DspConfig =
    copy(audioUpscalerEnabled = true, audioUpscalerAmount = sanitizeUpscalerAmount(amount))

fun DspConfig.withAudioUpscalerCutoff(hz: Float): DspConfig =
    copy(audioUpscalerEnabled = true, audioUpscalerCutoffHz = sanitizeUpscalerCutoff(hz))

/** Where the lost highs of a typical source start: common MP3 / AAC low-pass points shown as one-tap chips. */
data class AudioUpscalerSource(val label: String, val cutoffHz: Float)

val AUDIO_UPSCALER_SOURCES: List<AudioUpscalerSource> = listOf(
    AudioUpscalerSource("128 kbps", 16_000f),
    AudioUpscalerSource("192 kbps", 18_000f),
    AudioUpscalerSource("256 kbps+", 19_500f),
    AudioUpscalerSource("Old / AM", 12_000f)
)
