package com.beatraxus.app.model

import kotlin.math.roundToInt

/**
 * Studio DSP > Stem Mixer (replaces the old Spatial page).
 *
 * The mixer keeps one level, one mute and one solo per stem of the song. Everything here is pure settings logic; it
 * only edits [DspConfig]. The audio side is not connected yet: [STEM_SEPARATOR_AVAILABLE] is false until a
 * source-separation model is bundled and an engine stage reads [stemEffectiveGainsDb].
 */
enum class StemChannel(val label: String) {
    VOCALS("Vocals"),
    DRUMS("Drums"),
    BASS("Bass"),
    GUITAR("Guitar"),
    PIANO("Piano"),
    OTHER("Other")
}

const val STEM_COUNT = 6
const val STEM_GAIN_MIN_DB = -12f
const val STEM_GAIN_MAX_DB = 12f

/** Flip to true once the splitter model and its engine stage exist; the screen then drops its "preview" notes. */
const val STEM_SEPARATOR_AVAILABLE = false

val DEFAULT_STEM_GAINS_DB: List<Float> = List(STEM_COUNT) { 0f }

private const val STEM_MASK_ALL = (1 shl STEM_COUNT) - 1

/** Always six finite values inside the allowed range, snapped to 0.5 dB, whatever was stored. */
fun sanitizeStemGains(gains: List<Float>?): List<Float> = List(STEM_COUNT) { i ->
    val v = gains?.getOrNull(i)
    if (v == null || v.isNaN() || v.isInfinite()) 0f
    else (v.coerceIn(STEM_GAIN_MIN_DB, STEM_GAIN_MAX_DB) * 2f).roundToInt() / 2f
}

/** Keeps only the bits of the six stems (bit i = [StemChannel] ordinal i). */
fun sanitizeStemMask(mask: Int): Int = mask and STEM_MASK_ALL

private fun stemBit(index: Int): Int = 1 shl index

private fun Int.hasStem(index: Int): Boolean = ((this shr index) and 1) == 1

/**
 * Level each stem should play at (dB), or null when it must be silent: muted, or another stem is soloed.
 * Mute wins over solo for the same stem (the setters never produce both at once).
 */
fun DspConfig.stemEffectiveGainsDb(): List<Float?> {
    val gains = sanitizeStemGains(stemGainsDb)
    val muted = sanitizeStemMask(stemMuteMask)
    val solo = sanitizeStemMask(stemSoloMask)
    return List(STEM_COUNT) { i ->
        val audible = solo == 0 || solo.hasStem(i)
        if (!audible || muted.hasStem(i)) null else gains[i]
    }
}

/** A one-tap mix. Applying it resets every level to 0 dB and sets the mute / solo masks. */
data class StemMixPreset(
    val name: String,
    val description: String,
    val muteMask: Int = 0,
    val soloMask: Int = 0
)

val BUILT_IN_STEM_MIXES: List<StemMixPreset> = listOf(
    StemMixPreset("Original", "All parts as recorded."),
    StemMixPreset("Karaoke", "Vocals removed, the band plays on.", muteMask = stemBit(StemChannel.VOCALS.ordinal)),
    StemMixPreset("Acapella", "Only the voice.", soloMask = stemBit(StemChannel.VOCALS.ordinal)),
    StemMixPreset("No drums", "Everything except the drums. Play along.", muteMask = stemBit(StemChannel.DRUMS.ordinal)),
    StemMixPreset(
        "Rhythm only", "Drums and bass, nothing else.",
        soloMask = stemBit(StemChannel.DRUMS.ordinal) or stemBit(StemChannel.BASS.ordinal)
    )
)

/** Description line under the quick mixes: the active mix, or a hint for a hand-made one. */
fun stemMixDescription(activeName: String?): String =
    BUILT_IN_STEM_MIXES.firstOrNull { it.name == activeName }?.description ?: "Your own mix."

// ── Pure settings edits ───────────────────────────────────────────────────────────────────────────────────

fun DspConfig.withStemMixer(enabled: Boolean): DspConfig = copy(stemMixerEnabled = enabled)

/** One stem's level in dB (index = [StemChannel] ordinal); out-of-range indexes change nothing. */
fun DspConfig.withStemGain(index: Int, db: Float): DspConfig {
    if (index !in 0 until STEM_COUNT) return this
    val gains = sanitizeStemGains(stemGainsDb).toMutableList()
    gains[index] = sanitizeStemGains(listOf(db)).first() // same range and 0.5 dB snapping as every stored value
    return copy(stemGainsDb = gains)
}

/** Mutes or un-mutes a stem. Muting a soloed stem takes it out of solo. */
fun DspConfig.withStemMuteToggled(index: Int): DspConfig {
    if (index !in 0 until STEM_COUNT) return this
    val muted = sanitizeStemMask(stemMuteMask)
    val nowMuted = !muted.hasStem(index)
    return copy(
        stemMuteMask = if (nowMuted) muted or stemBit(index) else muted and stemBit(index).inv(),
        stemSoloMask = if (nowMuted) sanitizeStemMask(stemSoloMask) and stemBit(index).inv() else sanitizeStemMask(stemSoloMask)
    )
}

/** Solos or un-solos a stem. Soloing a muted stem un-mutes it. */
fun DspConfig.withStemSoloToggled(index: Int): DspConfig {
    if (index !in 0 until STEM_COUNT) return this
    val solo = sanitizeStemMask(stemSoloMask)
    val nowSolo = !solo.hasStem(index)
    return copy(
        stemSoloMask = if (nowSolo) solo or stemBit(index) else solo and stemBit(index).inv(),
        stemMuteMask = if (nowSolo) sanitizeStemMask(stemMuteMask) and stemBit(index).inv() else sanitizeStemMask(stemMuteMask)
    )
}

/** Every level to 0 dB, nothing muted or soloed. The on / off switch is kept. */
fun DspConfig.withStemMixReset(): DspConfig =
    copy(stemGainsDb = DEFAULT_STEM_GAINS_DB, stemMuteMask = 0, stemSoloMask = 0)

/** Applies a built-in quick mix by name; an unknown name changes nothing. */
fun DspConfig.withStemMixPreset(name: String): DspConfig {
    val mix = BUILT_IN_STEM_MIXES.firstOrNull { it.name == name } ?: return this
    return copy(
        stemGainsDb = DEFAULT_STEM_GAINS_DB,
        stemMuteMask = sanitizeStemMask(mix.muteMask),
        stemSoloMask = sanitizeStemMask(mix.soloMask)
    )
}

/** Name of the built-in quick mix the mixer is on right now (all levels 0 dB), or null for a hand-made mix. */
fun DspConfig.activeStemMix(): String? {
    if (sanitizeStemGains(stemGainsDb).any { it != 0f }) return null
    val muted = sanitizeStemMask(stemMuteMask)
    val solo = sanitizeStemMask(stemSoloMask)
    return BUILT_IN_STEM_MIXES.firstOrNull { it.muteMask == muted && it.soloMask == solo }?.name
}

// ── Saved form (one comma separated string, like the surround trims) ─────────────────────────────────────

fun serializeStemGains(gains: List<Float>?): String = sanitizeStemGains(gains).joinToString(",")

fun deserializeStemGains(raw: String?): List<Float> =
    sanitizeStemGains(raw?.split(',')?.mapNotNull { it.trim().toFloatOrNull() })
