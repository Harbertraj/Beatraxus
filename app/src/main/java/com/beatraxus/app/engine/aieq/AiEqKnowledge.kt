package com.beatraxus.app.engine.aieq

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin (no Android) knowledge base behind the AI EQ: genre / mood / language inference from song
 * metadata, per-genre and per-mood EQ curves, and the audio-measurement corrections. Free and fully offline.
 */
object AiEqKnowledge {
    /** Bump to make every cached profile get re-predicted. */
    const val ENGINE_VERSION = 2
    const val BAND_COUNT = 10
    const val MAX_GAIN_DB = 4.5f

    val BAND_FREQUENCIES = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /** 128-bin analyser spectrum -> 10 bands. */
    private val BAND_BIN_RANGES = arrayOf(
        0..0, 0..1, 1..2, 2..3, 4..6, 7..12, 13..25, 26..50, 51..95, 96..127
    )

    // ---- Genre curves (dB per band) -------------------------------------------------------------------------
    private val CURVES: Map<String, FloatArray> = mapOf(
        "Pop" to floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f, 0f, 0.3f, 0.5f, 0.8f, 0.5f),
        "EDM" to floatArrayOf(2.0f, 2.0f, 1.5f, 0.5f, -0.5f, 0f, 0f, 0.8f, 1.0f, 0.5f),
        "Hip-Hop" to floatArrayOf(2.5f, 2.0f, 1.2f, 0.5f, -0.3f, 0f, 0f, 0.5f, 0.8f, 0f),
        "Rock" to floatArrayOf(1.0f, 1.2f, 0.8f, 0f, -0.5f, 0f, 0.5f, 1.0f, 1.2f, 0.8f),
        "Metal" to floatArrayOf(1.0f, 1.0f, 0f, -0.8f, -1.0f, 0f, 0.5f, 1.2f, 1.0f, 0.5f),
        "Indian Film" to floatArrayOf(0.8f, 1.0f, 0.7f, 0.2f, 0f, 0.3f, 0.5f, 0.8f, 1.0f, 0.6f),
        "Devotional" to floatArrayOf(0.3f, 0.5f, 0.5f, 0.3f, 0f, 0.3f, 0.5f, 0.5f, 0.6f, 0.3f),
        "Classical" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.3f, 0.5f, 0.5f, 0.3f),
        "Jazz" to floatArrayOf(0.3f, 0.5f, 0.3f, 0f, 0f, 0.2f, 0.3f, 0.3f, 0.5f, 0.3f),
        "Acoustic" to floatArrayOf(0f, 0.3f, 0.3f, 0.2f, 0f, 0.3f, 0.5f, 0.5f, 0.5f, 0.3f),
        "R&B" to floatArrayOf(1.5f, 1.5f, 1.0f, 0.3f, 0f, -0.2f, 0.2f, 0.3f, 0.5f, 0.3f),
        "Lo-Fi" to floatArrayOf(0.5f, 0.5f, 0.3f, 0f, 0f, -0.3f, -0.5f, -0.8f, -1.0f, -1.2f),
        "Ambient" to floatArrayOf(0.5f, 0.5f, 0.3f, 0f, 0f, 0f, 0f, 0.2f, 0.5f, 0.5f),
        "Speech" to floatArrayOf(-3.0f, -2.5f, -1.5f, 0f, 0.5f, 1.2f, 1.8f, 1.0f, 0f, -1.0f),
        "Reggae" to floatArrayOf(1.8f, 1.5f, 0.8f, 0f, -0.3f, 0f, 0.3f, 0.5f, 0.5f, 0.2f),
        "K-Pop" to floatArrayOf(0.8f, 0.8f, 0.3f, 0f, -0.2f, 0f, 0.3f, 0.7f, 1.0f, 0.7f)
    )

    /** Keyword (lower-case) -> curve key. First match wins, so more specific entries come first. */
    private val GENRE_KEYWORDS: List<Pair<String, String>> = listOf(
        "devotional" to "Devotional", "bhajan" to "Devotional", "stotram" to "Devotional", "slokam" to "Devotional",
        "carnatic" to "Classical", "hindustani" to "Classical", "classical" to "Classical", "symphony" to "Classical",
        "orchestra" to "Classical", "opera" to "Classical", "baroque" to "Classical",
        "podcast" to "Speech", "audiobook" to "Speech", "spoken" to "Speech", "speech" to "Speech",
        "lo-fi" to "Lo-Fi", "lofi" to "Lo-Fi", "chillhop" to "Lo-Fi",
        "ambient" to "Ambient", "new age" to "Ambient", "drone" to "Ambient", "meditation" to "Ambient",
        "k-pop" to "K-Pop", "kpop" to "K-Pop", "j-pop" to "K-Pop", "jpop" to "K-Pop",
        "heavy metal" to "Metal", "metal" to "Metal", "hardcore" to "Metal", "grindcore" to "Metal",
        "hip-hop" to "Hip-Hop", "hip hop" to "Hip-Hop", "hiphop" to "Hip-Hop", "rap" to "Hip-Hop", "trap" to "Hip-Hop",
        "drill" to "Hip-Hop", "phonk" to "Hip-Hop",
        "dubstep" to "EDM", "techno" to "EDM", "trance" to "EDM", "house" to "EDM", "edm" to "EDM", "electro" to "EDM",
        "dance" to "EDM", "drum and bass" to "EDM", "dnb" to "EDM", "club" to "EDM", "remix" to "EDM",
        "r&b" to "R&B", "rnb" to "R&B", "soul" to "R&B", "funk" to "R&B", "disco" to "R&B", "neo-soul" to "R&B",
        "reggae" to "Reggae", "ska" to "Reggae", "dancehall" to "Reggae",
        "jazz" to "Jazz", "blues" to "Jazz", "swing" to "Jazz", "bossa" to "Jazz",
        "acoustic" to "Acoustic", "folk" to "Acoustic", "country" to "Acoustic", "unplugged" to "Acoustic",
        "singer-songwriter" to "Acoustic", "bluegrass" to "Acoustic",
        "rock" to "Rock", "punk" to "Rock", "grunge" to "Rock", "alternative" to "Rock", "indie" to "Rock",
        "emo" to "Rock", "shoegaze" to "Rock",
        "tamil" to "Indian Film", "kollywood" to "Indian Film", "hindi" to "Indian Film", "bollywood" to "Indian Film",
        "telugu" to "Indian Film", "tollywood" to "Indian Film", "malayalam" to "Indian Film",
        "kannada" to "Indian Film", "punjabi" to "Indian Film", "bengali" to "Indian Film",
        "film" to "Indian Film", "soundtrack" to "Indian Film", "ost" to "Indian Film", "from the movie" to "Indian Film",
        "pop" to "Pop", "instrumental" to "Classical"
    )

    /** Display genre for a curve key (what the UI shows when we only know the family). */
    private val DISPLAY_GENRE = mapOf("Indian Film" to "Film Music", "Speech" to "Spoken Word")

    // ---- Mood offsets (dB per band) ---------------------------------------------------------------------------
    private val MOOD_OFFSETS: Map<String, FloatArray> = mapOf(
        "Energetic" to floatArrayOf(0.8f, 0.8f, 0.4f, 0f, -0.2f, 0f, 0.2f, 0.5f, 0.6f, 0.3f),
        "Party" to floatArrayOf(1.0f, 1.0f, 0.5f, 0f, -0.3f, 0f, 0.2f, 0.5f, 0.7f, 0.4f),
        "Workout" to floatArrayOf(1.0f, 0.9f, 0.5f, 0f, -0.2f, 0f, 0.3f, 0.6f, 0.6f, 0.3f),
        "Aggressive" to floatArrayOf(0.6f, 0.6f, 0.2f, -0.3f, -0.3f, 0.2f, 0.5f, 0.8f, 0.4f, 0f),
        "Happy" to floatArrayOf(0.2f, 0.2f, 0f, 0f, 0f, 0.1f, 0.2f, 0.4f, 0.6f, 0.5f),
        "Calm" to floatArrayOf(0.2f, 0.3f, 0.3f, 0.2f, 0f, 0f, -0.1f, -0.3f, -0.4f, -0.3f),
        "Sleep" to floatArrayOf(0.3f, 0.4f, 0.4f, 0.2f, 0f, -0.2f, -0.4f, -0.7f, -0.9f, -0.8f),
        "Meditation" to floatArrayOf(0.2f, 0.3f, 0.4f, 0.3f, 0.1f, 0f, -0.2f, -0.4f, -0.4f, -0.2f),
        "Sad" to floatArrayOf(0.4f, 0.5f, 0.6f, 0.4f, 0.1f, -0.1f, -0.2f, -0.3f, -0.2f, -0.2f),
        "Romantic" to floatArrayOf(0.3f, 0.5f, 0.6f, 0.4f, 0.1f, 0.1f, 0f, -0.1f, 0.1f, 0.2f),
        "Emotional" to floatArrayOf(0.3f, 0.4f, 0.5f, 0.4f, 0.2f, 0.1f, 0.1f, 0f, 0f, 0f),
        "Focus" to floatArrayOf(-0.4f, -0.3f, 0f, 0.2f, 0.4f, 0.5f, 0.4f, 0.1f, 0f, -0.2f),
        "Epic" to floatArrayOf(0.8f, 0.8f, 0.4f, 0f, -0.2f, 0f, 0.2f, 0.4f, 0.6f, 0.6f),
        "Dark" to floatArrayOf(0.6f, 0.6f, 0.4f, 0.1f, 0f, -0.1f, -0.3f, -0.6f, -0.8f, -0.7f),
        "Motivational" to floatArrayOf(0.6f, 0.6f, 0.3f, 0f, -0.1f, 0.1f, 0.3f, 0.5f, 0.5f, 0.3f)
    )

    private val MOOD_KEYWORDS: List<Pair<String, String>> = listOf(
        "lullaby" to "Sleep", "sleep" to "Sleep", "meditat" to "Meditation", "relax" to "Calm", "chill" to "Calm",
        "calm" to "Calm", "peace" to "Calm", "rain" to "Calm",
        "party" to "Party", "club" to "Party", "dance" to "Party", "workout" to "Workout", "gym" to "Workout",
        "pump" to "Workout", "fire" to "Energetic", "energy" to "Energetic", "power" to "Energetic",
        "rage" to "Aggressive", "war" to "Aggressive", "kill" to "Aggressive", "angry" to "Aggressive",
        "love" to "Romantic", "kiss" to "Romantic", "heart" to "Romantic", "romance" to "Romantic",
        "kadhal" to "Romantic", "pyaar" to "Romantic", "ishq" to "Romantic", "mohabbat" to "Romantic",
        "sad" to "Sad", "cry" to "Sad", "tears" to "Sad", "alone" to "Sad", "goodbye" to "Sad", "miss you" to "Sad",
        "pain" to "Sad", "broken" to "Sad",
        "happy" to "Happy", "sunshine" to "Happy", "smile" to "Happy", "joy" to "Happy", "celebrate" to "Happy",
        "focus" to "Focus", "study" to "Focus", "epic" to "Epic", "hero" to "Epic", "victory" to "Epic",
        "dark" to "Dark", "night" to "Dark", "shadow" to "Dark"
    )

    private val GENRE_DEFAULT_MOOD = mapOf(
        "EDM" to "Party", "Hip-Hop" to "Energetic", "Rock" to "Energetic", "Metal" to "Aggressive",
        "Classical" to "Calm", "Ambient" to "Calm", "Lo-Fi" to "Calm", "Jazz" to "Calm", "Acoustic" to "Calm",
        "Devotional" to "Calm", "R&B" to "Romantic", "Speech" to "Focus", "Reggae" to "Happy", "K-Pop" to "Happy",
        "Pop" to "Happy", "Indian Film" to "Romantic"
    )

    // ---- Inference --------------------------------------------------------------------------------------------

    data class Inferred(val curveKey: String, val genre: String, val mood: String, val language: String, val known: Boolean)

    /** Genre curve key + display names from every piece of text we have for a song. */
    fun inferFromMetadata(
        genreTag: String,
        title: String,
        artist: String,
        album: String,
        folder: String,
        fileName: String = ""
    ): Inferred {
        val tag = normalizeTag(genreTag)
        val tagKey = tag?.let { matchGenre(it, strict = false) }
        // Artist / album / folder / file name describe the genre; a title only counts for unambiguous words
        // ("House of Cards" must not become EDM).
        val descriptive = " ${artist.lowercase()} ${album.lowercase()} ${folder.lowercase()} ${fileName.lowercase()} "
        val titleLower = title.lowercase()
        val titleKey = TITLE_GENRE_WORDS.firstOrNull { Regex("(^|[^a-z])${Regex.escape(it.first)}([^a-z]|$)").containsMatchIn(titleLower) }?.second
        val textKey = if (tagKey == null) (matchGenre(descriptive, strict = true) ?: titleKey) else null
        val key = tagKey ?: textKey
        val textBlob = " $titleLower$descriptive"
        val language = detectLanguage("$title $artist $album", "$genreTag $folder")
        val curveKey = key ?: when (language) {
            "Tamil", "Hindi", "Telugu", "Malayalam", "Kannada", "Punjabi", "Bengali" -> "Indian Film"
            "Korean", "Japanese" -> "K-Pop"
            else -> "Pop"
        }
        val genre = when {
            tag != null && tagKey != null -> tag.replaceFirstChar { it.uppercase() }
            else -> DISPLAY_GENRE[curveKey] ?: curveKey
        }
        val mood = matchMood(textBlob) ?: GENRE_DEFAULT_MOOD[curveKey] ?: "Happy"
        return Inferred(curveKey, genre, mood, language, known = key != null)
    }

    private fun normalizeTag(tag: String): String? {
        val t = tag.trim()
        if (t.isEmpty()) return null
        val l = t.lowercase()
        if (l in setOf("unknown", "<unknown>", "other", "genre", "none", "n/a", "0", "(0)")) return null
        // ID3v1 numeric genre like "(17)" is not usable
        if (l.all { it.isDigit() || it == '(' || it == ')' }) return null
        return t.take(32)
    }

    private val TITLE_GENRE_WORDS = listOf(
        "remix" to "EDM", "lofi" to "Lo-Fi", "lo-fi" to "Lo-Fi", "acoustic" to "Acoustic", "unplugged" to "Acoustic",
        "instrumental" to "Classical", "karaoke" to "Pop", "devotional" to "Devotional", "bhajan" to "Devotional"
    )

    /** [strict] = free text (file names, folders): keywords must start a word. Tags may match inside a word. */
    private fun matchGenre(text: String, strict: Boolean): String? {
        val l = text.lowercase()
        for ((kw, key) in GENRE_KEYWORDS) {
            val esc = Regex.escape(kw)
            val hit = when {
                kw.length <= 4 -> Regex("(^|[^a-z])$esc([^a-z]|$)").containsMatchIn(l)
                strict -> Regex("(^|[^a-z])$esc").containsMatchIn(l)
                else -> l.contains(kw)
            }
            if (hit) return key
        }
        return null
    }

    private fun matchMood(text: String): String? {
        val l = text.lowercase()
        for ((kw, mood) in MOOD_KEYWORDS) {
            if (Regex("(^|[^a-z])${Regex.escape(kw)}").containsMatchIn(l)) return mood
        }
        return null
    }

    /** Language from the Unicode script of the text (reliable), then a few transliterated hints. */
    fun detectLanguage(text: String, hints: String = ""): String {
        val counts = HashMap<String, Int>()
        for (ch in text) {
            val lang = when (ch.code) {
                in 0x0B80..0x0BFF -> "Tamil"
                in 0x0900..0x097F -> "Hindi"
                in 0x0C00..0x0C7F -> "Telugu"
                in 0x0D00..0x0D7F -> "Malayalam"
                in 0x0C80..0x0CFF -> "Kannada"
                in 0x0A00..0x0A7F -> "Punjabi"
                in 0x0980..0x09FF -> "Bengali"
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> "Korean"
                in 0x3040..0x30FF -> "Japanese"
                in 0x4E00..0x9FFF -> "Chinese"
                in 0x0600..0x06FF -> "Urdu"
                in 0x0400..0x04FF -> "Russian"
                else -> null
            }
            if (lang != null) counts.merge(lang, 1, Int::plus)
        }
        counts.maxByOrNull { it.value }?.let { return it.key }
        val l = " ${text.lowercase()} ${hints.lowercase()} "
        return when {
            listOf("tamil", "kaatru", "kanmani", "kadhal", "vaa ", "nenjam", "anirudh", "rahman", "ilaiyaraaja").any { it in l } -> "Tamil"
            listOf("hindi", "pyaar", "ishq", "dil ", "tum ", "mohabbat", "arijit", "bollywood").any { it in l } -> "Hindi"
            "telugu" in l -> "Telugu"
            "malayalam" in l -> "Malayalam"
            "kannada" in l -> "Kannada"
            "punjabi" in l -> "Punjabi"
            else -> "English"
        }
    }

    // ---- Gains ------------------------------------------------------------------------------------------------

    /** Facts about the file that shape the curve even when the audio itself can't be read. */
    data class FileTraits(val year: Int = 0, val bitrateKbps: Int = 0, val lossy: Boolean = false)

    /** Measured audio facts (all optional - null when the audio couldn't be analysed). */
    data class Measured(
        val spectral128: FloatArray?,
        val lufs: Float,
        val dynamicRangeDb: Float,
        val bassScore: Float,
        val midScore: Float,
        val trebleScore: Float,
        val tempoBpm: Float
    )

    /**
     * Tempo and key of the song (all optional - confidence 0 means "unknown, don't use"). They add a small,
     * continuous per-song offset on top of genre + mood so two songs of one genre still get different curves.
     */
    data class MusicTraits(
        val bpm: Float = 0f,
        val bpmConfidence: Float = 0f,
        val tonic: Int = 0,
        val minor: Boolean = false,
        val keyConfidence: Float = 0f
    )

    /**
     * Fast tracks get a tighter, punchier low end and more presence; slow ones get warmth and a softer top.
     * Minor keys lean darker / warmer, major keys brighter. A tonic whose bass fundamental sits right on the
     * 62 Hz band is not boosted there (a boost would make that one note boom).
     */
    fun musicAdjustment(m: MusicTraits): FloatArray {
        val adj = FloatArray(BAND_COUNT)
        val tempo = ((m.bpm - 120f) / 50f).coerceIn(-1f, 1f) * m.bpmConfidence.coerceIn(0f, 1f)
        if (m.bpm > 0f && tempo != 0f) {
            if (tempo > 0f) {
                adj[0] -= 0.2f * tempo; adj[1] -= 0.2f * tempo
                adj[2] += 0.3f * tempo; adj[3] -= 0.3f * tempo
                adj[6] += 0.15f * tempo; adj[7] += 0.25f * tempo
            } else {
                val s = -tempo
                adj[1] += 0.25f * s; adj[2] += 0.3f * s; adj[3] += 0.2f * s
                adj[8] -= 0.15f * s; adj[9] -= 0.15f * s
            }
        }
        val k = m.keyConfidence.coerceIn(0f, 1f)
        if (k > 0f) {
            if (m.minor) {
                adj[2] += 0.15f * k; adj[3] += 0.1f * k; adj[8] -= 0.15f * k; adj[9] -= 0.1f * k
            } else {
                adj[6] += 0.1f * k; adj[7] += 0.15f * k; adj[8] += 0.15f * k
            }
            val d = abs(ln(AiEqMusicAnalysis.tonicBassHz(m.tonic) / BAND_FREQUENCIES[1]) / ln(2f)) // 0 .. ~0.5 octave
            val proximity = (1f - d / 0.25f).coerceIn(0f, 1f)
            adj[1] -= 0.3f * k * proximity
            adj[2] -= 0.15f * k * proximity
        }
        for (i in adj.indices) adj[i] = adj[i].coerceIn(-0.6f, 0.6f)
        return adj
    }

    fun curveFor(curveKey: String): FloatArray = (CURVES[curveKey] ?: CURVES.getValue("Pop")).copyOf()
    fun moodOffsetFor(mood: String): FloatArray = (MOOD_OFFSETS[mood] ?: FloatArray(BAND_COUNT)).copyOf()

    fun buildGains(inferred: Inferred, traits: FileTraits, measured: Measured?, music: MusicTraits? = null): FloatArray {
        val genre = curveFor(inferred.curveKey)
        val mood = moodOffsetFor(inferred.mood)
        val g = FloatArray(BAND_COUNT) { genre[it] * 0.9f + mood[it] * 0.6f }

        // Age of the master: older recordings are duller.
        if (traits.year in 1..1999) {
            val age = if (traits.year < 1985) 0.8f else 0.5f
            g[8] += age; g[9] += age * 0.8f; g[7] += age * 0.4f
        }

        if (measured != null) {
            val corr = spectralCorrection(measured)
            for (i in 0 until BAND_COUNT) g[i] += corr[i]
            // Loud / heavily compressed masters: calm the harsh region and the low-end build-up.
            if (measured.dynamicRangeDb in 1f..7f) { g[6] -= 0.3f; g[7] -= 0.5f; g[8] -= 0.4f }
            if (measured.lufs > -9f) { g[0] -= 0.4f; g[1] -= 0.4f }
            if (measured.dynamicRangeDb > 16f) { g[2] += 0.2f; g[9] += 0.2f }
        }

        if (music != null) {
            val adj = musicAdjustment(music)
            for (i in 0 until BAND_COUNT) g[i] += adj[i]
        }

        // Low-bitrate lossy files have little or no air: boosting it only raises the codec noise.
        val lowBitrate = traits.lossy && traits.bitrateKbps in 1..160
        if (lowBitrate) { g[9] = min(g[9], 0.2f); g[8] = min(g[8], 0.6f) }

        // light 3-point smoothing so neighbouring bands never fight each other
        val sm = FloatArray(BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            val p = g[if (i > 0) i - 1 else i]
            val n = g[if (i < BAND_COUNT - 1) i + 1 else i]
            sm[i] = 0.2f * p + 0.6f * g[i] + 0.2f * n
        }
        return FloatArray(BAND_COUNT) { (Math.round(sm[it].coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) * 10f) / 10f) }
    }

    /**
     * Pushes the song towards a balanced spectrum. Music has a natural downward tilt, so a straight line over
     * log-frequency is removed first and only the *deviation* from it (a muffled top end, a boomy bass...)
     * is corrected, at 35 % strength and +/-2 dB.
     */
    fun spectralCorrection(m: Measured): FloatArray {
        val out = FloatArray(BAND_COUNT)
        val spec = m.spectral128
        val rel = if (spec != null && spec.size >= 128 && spec.any { it > 1e-6f }) bandRelativeDb(spec) else null
        if (rel != null) {
            // Robust (Theil-Sen) line over band index - bands are ~octave spaced, i.e. linear in log-frequency.
            // Median-based, so a cliff or a bump in a couple of bands cannot drag the reference line with it.
            val slopes = ArrayList<Float>()
            for (i in 0 until BAND_COUNT) for (j in i + 1 until BAND_COUNT) slopes.add((rel[j] - rel[i]) / (j - i))
            val slope = median(slopes)
            val intercept = median((0 until BAND_COUNT).map { rel[it] - slope * it })
            for (i in 0 until BAND_COUNT) {
                val residual = rel[i] - (intercept + slope * i)
                out[i] = (-0.35f * residual).coerceIn(-2f, 2f)
            }
        } else {
            // No spectrum, only coarse bass/mid/treble shares.
            val total = max(m.bassScore + m.midScore + m.trebleScore, 1e-3f)
            val b = m.bassScore / total; val t = m.trebleScore / total
            val bassBias = ((b - 0.33f) * -4f).coerceIn(-1.5f, 1.5f)
            val trebleBias = ((t - 0.33f) * -4f).coerceIn(-1.5f, 1.5f)
            for (i in 0..2) out[i] = bassBias
            for (i in 7..9) out[i] = trebleBias
        }
        return out
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    fun bandRelativeDb(spec: FloatArray): FloatArray {
        val energies = FloatArray(BAND_COUNT) { i ->
            var sum = 0f; var c = 0
            for (bin in BAND_BIN_RANGES[i]) if (bin in spec.indices) { sum += spec[bin]; c++ }
            if (c > 0) sum / c else 1e-3f
        }
        val avg = max(energies.average().toFloat(), 1e-6f)
        return FloatArray(BAND_COUNT) { (20f * log10(max(energies[it], 1e-6f) / avg)).coerceIn(-30f, 30f) }
    }

    fun confidence(inferred: Inferred, measured: Boolean, music: MusicTraits? = null): Float {
        val base = when {
            measured && inferred.known -> 0.9f
            measured -> 0.8f
            inferred.known -> 0.6f
            else -> 0.35f
        }
        // Reliable tempo AND key agree with the measured spectrum -> a little more trust.
        val bonus = if (measured && music != null && music.bpmConfidence >= 0.5f && music.keyConfidence >= 0.5f) 0.05f else 0f
        return min(0.95f, base + bonus)
    }

    /** AI EQ gain at any frequency: log-frequency interpolation between the ten bands (flat beyond the ends). */
    fun gainAt(gains: FloatArray, freqHz: Float): Float {
        if (gains.size != BAND_COUNT || freqHz <= 0f) return 0f
        val f = freqHz.coerceIn(BAND_FREQUENCIES.first(), BAND_FREQUENCIES.last())
        for (i in 0 until BAND_COUNT - 1) {
            val lo = BAND_FREQUENCIES[i]
            val hi = BAND_FREQUENCIES[i + 1]
            if (f <= hi) {
                val t = (ln(f / lo) / ln(hi / lo)).coerceIn(0f, 1f)
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return gains.last()
    }

    /** Largest absolute difference between two gain sets - handy for tests and "did it change" checks. */
    fun maxDelta(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in 0 until min(a.size, b.size)) d = max(d, abs(a[i] - b[i]))
        return d
    }
}
