package com.beatraxus.app.engine.aieq

/**
 * Per-song AI EQ prediction. Ten gains in dB for 31 Hz ... 16 kHz ([AiEqKnowledge.BAND_FREQUENCIES]).
 *
 * Profiles live in [AiEqStore] (a small JSON file), NOT in the Room database, so saving one can never fail
 * because the song is missing from the `songs` table (cloud / streamed / add-on songs).
 */
data class AiEqProfile(
    val songId: String,
    val genre: String,
    val mood: String,
    val language: String,
    val gains: FloatArray,
    /** 0..1 - how much evidence the prediction is based on (audio measured > tags > guess). */
    val confidence: Float,
    val source: Source,
    val version: Int = AiEqKnowledge.ENGINE_VERSION,
    val createdAt: Long = System.currentTimeMillis()
) {
    enum class Source { AUDIO, METADATA }

    init {
        require(gains.size == AiEqKnowledge.BAND_COUNT) { "AI EQ needs ${AiEqKnowledge.BAND_COUNT} gains" }
    }

    override fun equals(other: Any?): Boolean =
        other is AiEqProfile && songId == other.songId && genre == other.genre && mood == other.mood &&
            language == other.language && gains.contentEquals(other.gains) && source == other.source &&
            version == other.version

    override fun hashCode(): Int {
        var h = songId.hashCode()
        h = 31 * h + gains.contentHashCode()
        h = 31 * h + version
        return h
    }
}
