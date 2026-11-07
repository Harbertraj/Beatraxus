package com.beatraxus.app.model

/**
 * Stem Mixer quality. Each level is one separation engine with its own stem list and its own cache folder.
 *
 * - [FAST]: SCNet int8, 4 stems (Vocals, Drums, Bass, Other).
 * - [MAX]: HTDemucs 6-source, 6 stems (Vocals, Drums, Bass, Guitar, Piano, Other).
 *
 * [engineId] is written in the cache "done" marker, so a folder only counts as cached for the engine that made it.
 */
enum class StemQuality(val label: String, val engineId: String, val folderName: String) {
    FAST("Fast", "scnet-int8", "fast"),
    MAX("Max", "htdemucs6s", "max");

    companion object {
        /** Tolerant parse of a stored value; anything unknown falls back to [FAST]. */
        fun fromStored(raw: String?): StemQuality =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: FAST
    }
}

/**
 * One stem as the app knows it. [channel] is the slot in the persisted 6-slot mixer settings (gain / mute / solo),
 * [key] names the cache file, [colorArgb] is the strip colour. Names, order and colours are defined here and nowhere
 * else, so the separator, the cache, the mixer DSP and the screen cannot disagree.
 */
data class StemSpec(
    val key: String,
    val label: String,
    val channel: StemChannel,
    val colorArgb: Long
) {
    val fileName: String get() = "$key.pcm"
}

/** What the mixer shows for one strip: a settings slot plus the label and colour to draw. */
data class StemStrip(val channel: StemChannel, val label: String, val colorArgb: Long)

object StemSpecs {
    private val VOCALS = StemSpec("vocals", "Vocals", StemChannel.VOCALS, 0xFF42A5F5)
    private val DRUMS = StemSpec("drums", "Drums", StemChannel.DRUMS, 0xFFFF7043)
    private val BASS = StemSpec("bass", "Bass", StemChannel.BASS, 0xFF26C6DA)
    private val GUITAR = StemSpec("guitar", "Guitar", StemChannel.GUITAR, 0xFF66BB6A)
    private val PIANO = StemSpec("piano", "Piano", StemChannel.PIANO, 0xFFEC407A)
    private val OTHER = StemSpec("other", "Other", StemChannel.OTHER, 0xFF9575CD)

    /** Stem order of the Fast engine; this is also the order of its cache files and of the arrays the DSP uses. */
    val FAST: List<StemSpec> = listOf(VOCALS, DRUMS, BASS, OTHER)

    /** Stem order of the Max engine. */
    val MAX: List<StemSpec> = listOf(VOCALS, DRUMS, BASS, GUITAR, PIANO, OTHER)

    /** Source order of the SCNet model output. */
    val SCNET_MODEL_ORDER: List<String> = listOf("drums", "bass", "other", "vocals")

    /** Source order of the htdemucs_6s model output. */
    val HTDEMUCS_6S_MODEL_ORDER: List<String> = listOf("drums", "bass", "other", "vocals", "guitar", "piano")

    const val COMBINED_OTHER_LABEL = "Other + Guitar + Piano"

    fun forQuality(quality: StemQuality): List<StemSpec> = when (quality) {
        StemQuality.FAST -> FAST
        StemQuality.MAX -> MAX
    }

    /** For each app stem (in [specs] order) the index of that source in the model output, or -1 if it is missing. */
    fun appToModelSources(specs: List<StemSpec>, modelOrder: List<String>): IntArray =
        IntArray(specs.size) { modelOrder.indexOf(specs[it].key) }

    /**
     * The strips the mixer draws for an engine. With [combineOther] on the Max engine, Guitar and Piano fold into one
     * "Other + Guitar + Piano" strip, so the mixer behaves like the 4-stem one.
     */
    fun strips(quality: StemQuality, combineOther: Boolean): List<StemStrip> {
        val specs = forQuality(quality)
        val fold = combineOther && quality == StemQuality.MAX
        return specs.mapNotNull { spec ->
            when {
                fold && (spec.channel == StemChannel.GUITAR || spec.channel == StemChannel.PIANO) -> null
                fold && spec.channel == StemChannel.OTHER -> StemStrip(spec.channel, COMBINED_OTHER_LABEL, spec.colorArgb)
                else -> StemStrip(spec.channel, spec.label, spec.colorArgb)
            }
        }
    }
}
