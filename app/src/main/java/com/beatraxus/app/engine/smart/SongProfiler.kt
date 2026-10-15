package com.beatraxus.app.engine.smart

import kotlin.math.max
import kotlin.math.min

/**
 * Everything the profiler knows about one song. Plain values only (no Android types) so the whole
 * thing runs - and is unit-tested - on the JVM.
 */
data class ProfileInput(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val folder: String = "",
    /** Embedded / stored genre tag of the song (may be junk, multi-valued or "Unknown"). */
    val tagGenre: String = "",
    val year: Int = 0,
    /** Genre an earlier analysis stored (model, online lookup or crude rule): a weak hint only. */
    val aiGenre: String? = null,
    val aiGenreConfidence: Float = 0f,
    val aiSecondaryGenre: String? = null,
    /** Language stored by an earlier analysis (unreliable default "English"). */
    val language: String? = null,
    /** Mood tags from a trusted source (online community tags, trained model), not from BPM rules. */
    val moodTags: List<String> = emptyList(),
    /** True when the numbers below were measured from the real audio. */
    val measured: Boolean = false,
    val tempoBpm: Float = 0f,
    val lufs: Float = -14f,
    val dynamicRange: Float = 10f,
    val bass: Float = 0.33f,
    val mid: Float = 0.33f,
    val treble: Float = 0.33f,
    val stereoWidth: Float = 1f
)

data class SongProfile(
    /** Clean genres, best first. ["Unknown"] only when nothing at all can be told. */
    val genres: List<String>,
    /** AI "sound styles": extra, more specific groups such as "Late Night Drive" or "Tamil Melody". */
    val styles: List<String>,
    /** Moods, best first. Empty only for a song nothing is known about. */
    val moods: List<String>,
    val moodScores: Map<String, Float>,
    val language: String?,
    val energy: Float,
    val valence: Float
) {
    /** What the Genres library groups by: genres plus the styles with the "✦ " marker. */
    val labels: List<String> = buildList {
        addAll(genres)
        styles.forEach { add(SongProfiler.STYLE_PREFIX + it) }
    }

    val primaryGenre: String get() = genres.firstOrNull() ?: GenreTaxonomy.UNKNOWN
}

object MoodCatalog {
    val CLASSIC = listOf(
        "Sleep", "Calm", "Focus", "Energetic", "Workout", "Happy", "Sad", "Romantic", "Party",
        "Motivational", "Aggressive", "Meditation", "Emotional", "Epic", "Dark"
    )
    /** New, more specific moods created by the in-app AI. */
    val NEW = listOf(
        "Late Night", "Rainy Day", "Road Trip", "Heartbreak", "Sunrise", "Nostalgia",
        "Beast Mode", "Dreamy", "Soulful", "Devotional"
    )
    val ALL = CLASSIC + NEW

    private val byKey = ALL.associateBy { it.lowercase() }
    fun canonical(tag: String): String? = byKey[tag.trim().lowercase()]
}

/**
 * In-app "AI" for the Genres and Mood libraries.
 *
 * It does not trust any single weak source. Genre: the song's own tag (cleaned up) wins, then the
 * stored / online AI genre, keywords in the title/folder, the song's language, and last the sound
 * itself. Mood: the measured sound (energy, brightness, pace, dynamics...) is scored against every
 * mood with a small valence/arousal model, then adjusted by genre, title words and online tags.
 * Contradicting moods (Sleep + Workout, Happy + Sad) are never given to the same song.
 */
object SongProfiler {

    const val STYLE_PREFIX = "✦ "

    // ------------------------------------------------------------------ helpers
    private fun clamp(x: Float, lo: Float = 0f, hi: Float = 1f) = max(lo, min(hi, x))
    private fun high(x: Float, a: Float, b: Float) = clamp((x - a) / (b - a))
    private fun low(x: Float, a: Float, b: Float) = 1f - high(x, a, b)
    /** Trapezoid: 0 below a, ramps to 1 at b, stays 1 until c, ramps to 0 at d. */
    private fun mid(x: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
        x <= a || x >= d -> 0f
        x < b -> (x - a) / (b - a)
        x <= c -> 1f
        else -> (d - x) / (d - c)
    }

    private class Sound(
        val measured: Boolean,
        val energy: Float, val valence: Float, val acoustic: Float, val pace: Float,
        val loud: Float, val dr: Float, val bright: Float, val bassy: Float, val width: Float
    )

    private fun soundOf(i: ProfileInput): Sound {
        if (!i.measured) {
            return Sound(false, 0.5f, 0.5f, 0.4f, 0.5f, 0.5f, 0.5f, 0.4f, 0.3f, 0.5f)
        }
        val loud = clamp((i.lufs + 28f) / 20f)
        val dr = clamp((i.dynamicRange - 4f) / 14f)
        val bpm = if (i.tempoBpm > 175f) i.tempoBpm / 2f else i.tempoBpm
        val pace = if (bpm <= 0f) 0.5f else clamp((bpm - 60f) / 80f)
        val tot = (i.bass + i.mid + i.treble).let { if (it < 0.01f) 1f else it }
        val bassy = clamp((i.bass / tot - 0.2f) / 0.35f)
        val bright = clamp((i.treble / tot - 0.15f) / 0.35f)
        val energy = 0.30f * loud + 0.22f * (1f - dr) + 0.25f * pace + 0.13f * bassy + 0.10f * bright
        val valence = 0.30f * bright + 0.28f * pace + 0.22f * energy + 0.20f * (1f - bassy * 0.6f)
        val acoustic = 0.45f * dr + 0.25f * (1f - loud) + 0.30f * (1f - bassy)
        val width = clamp((i.stereoWidth - 0.4f) / 1.0f)
        return Sound(true, clamp(energy), clamp(valence), clamp(acoustic), pace, loud, dr, bright, bassy, width)
    }

    /** " title album folder tag " in plain lower case words, for keyword matching. */
    private fun textOf(i: ProfileInput): String =
        " " + GenreTaxonomy.key("${i.title} ${i.album} ${i.folder} ${i.tagGenre}") + " "

    /** Same plus the artist name ("Murugan Devotional Songs", "Karaoke Hits"): for genre keywords only. */
    private fun genreTextOf(i: ProfileInput): String =
        " " + GenreTaxonomy.key("${i.title} ${i.artist} ${i.album} ${i.folder} ${i.tagGenre}") + " "

    private fun hasWord(text: String, vararg words: String) = words.any { text.contains(" ${GenreTaxonomy.key(it)} ") }

    // ------------------------------------------------------------------ genres
    private val WEAK_AI_GENRES = setOf("Pop", "Rock", "Hip-Hop", "EDM", "Classical")

    private val GENRE_CUES: List<Pair<List<String>, Pair<String, Float>>> = listOf(
        listOf("lofi", "lo fi") to ("Lo-Fi" to 0.6f),
        listOf("instrumental", "karaoke") to ("Instrumental" to 0.6f),
        listOf("podcast", "episode") to ("Podcast" to 0.7f),
        listOf("audiobook", "chapter") to ("Audiobook" to 0.7f),
        listOf("devotional", "bhajan", "slokam", "stotram", "suprabhatam", "aarti", "kirtan", "ayyappan", "murugan",
            "perumal", "amman", "hanuman chalisa", "gayatri", "om namah", "namah shivaya", "venkatesa") to ("Devotional" to 0.75f),
        listOf("ost", "original soundtrack", "background score", "bgm", "main title") to ("Soundtrack" to 0.5f),
        listOf("ghazal", "qawwali", "sufi") to ("Ghazal & Sufi" to 0.7f),
        listOf("carnatic", "hindustani", "raga") to ("Indian Classical" to 0.65f),
        listOf("remix", "club mix", "dj mix", "edm") to ("EDM" to 0.35f),
        listOf("symphony", "concerto", "sonata", "orchestra", "orchestral") to ("Classical" to 0.55f),
        listOf("reggae") to ("Reggae" to 0.5f),
        listOf("gospel", "worship") to ("Gospel" to 0.5f),
        listOf("kuthu", "gaana", "gana") to ("Tamil Folk" to 0.55f)
    )

    private fun scoreGenres(i: ProfileInput, s: Sound, text: String, language: String?): List<Pair<String, Float>> {
        val scores = LinkedHashMap<String, Float>()
        fun add(g: String, w: Float) {
            val old = scores[g]
            scores[g] = if (old == null) w else min(1.2f, max(old, w) + 0.15f)
        }

        GenreTaxonomy.canonical(i.tagGenre).forEachIndexed { idx, g -> add(g, if (idx == 0) 1.0f else 0.8f) }

        GenreTaxonomy.canonical(i.aiGenre).firstOrNull()?.let { g ->
            val w = when {
                i.aiGenreConfidence >= 0.5f -> 0.85f
                g in WEAK_AI_GENRES -> 0.30f
                else -> 0.50f
            }
            add(g, w)
        }
        GenreTaxonomy.canonical(i.aiSecondaryGenre).firstOrNull()?.let { add(it, 0.30f) }

        for ((words, target) in GENRE_CUES) {
            if (hasWord(text, *words.toTypedArray())) add(target.first, target.second)
        }

        // A regional film song tagged only "Pop"/"Unknown" still belongs with its film music.
        when (language) {
            "Tamil" -> add("Tamil Film Music", 0.5f)
            "Hindi" -> add("Hindi Film Music", 0.5f)
            "Telugu" -> add("Telugu Film Music", 0.5f)
            "Malayalam" -> add("Malayalam Film Music", 0.5f)
            "Kannada" -> add("Kannada Film Music", 0.5f)
            "Punjabi" -> add("Punjabi", 0.5f)
            "Korean" -> if (scores.values.none { it >= 0.6f }) add("K-Pop", 0.45f)
            "Japanese" -> if (scores.values.none { it >= 0.6f }) add("J-Pop", 0.45f)
        }

        if (i.year in 1..1994 && GenreTaxonomy.canonical(i.tagGenre).isEmpty()) add("Oldies", 0.3f)

        // Last resort: the sound itself.
        if (s.measured && scores.values.none { it >= 0.6f }) {
            val bpm = if (i.tempoBpm > 175f) i.tempoBpm / 2f else i.tempoBpm
            if (s.bassy >= 0.5f && i.tempoBpm in 115f..150f && s.loud >= 0.6f) add("EDM", 0.35f)
            if (s.bassy >= 0.5f && (i.tempoBpm in 65f..105f || i.tempoBpm in 130f..170f) && s.dr < 0.45f) add("Hip-Hop", 0.3f)
            if (s.dr >= 0.65f && s.loud <= 0.45f && s.bright < 0.5f) add("Classical", 0.3f)
            if (s.energy < 0.2f && s.dr > 0.5f) add("Ambient", 0.3f)
            if (s.bright >= 0.45f && s.energy >= 0.65f && s.bassy < 0.5f) add("Rock", 0.3f)
            if (s.bright >= 0.6f && s.energy >= 0.85f && s.dr < 0.25f) add("Metal", 0.3f)
            if (s.acoustic >= 0.65f && s.energy < 0.45f && bpm > 0f) add("Acoustic", 0.3f)
            if (scores.isEmpty()) add("Pop", 0.26f)
        }

        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun pickGenres(ranked: List<Pair<String, Float>>): List<String> {
        val strong = ranked.filter { it.second >= 0.4f }.take(3).map { it.first }
        if (strong.isNotEmpty()) return strong
        val best = ranked.firstOrNull()
        return if (best != null && best.second >= 0.25f) listOf(best.first) else listOf(GenreTaxonomy.UNKNOWN)
    }

    // ------------------------------------------------------------------ moods
    private val GENRE_MOOD_PRIOR: Map<String, Map<String, Float>> = mapOf(
        "Metal" to mapOf("Aggressive" to 0.30f, "Beast Mode" to 0.25f, "Energetic" to 0.15f, "Dark" to 0.15f),
        "Punk" to mapOf("Aggressive" to 0.20f, "Energetic" to 0.20f),
        "Hip-Hop" to mapOf("Workout" to 0.10f, "Beast Mode" to 0.10f, "Party" to 0.10f, "Energetic" to 0.10f),
        "Phonk" to mapOf("Beast Mode" to 0.25f, "Dark" to 0.15f, "Late Night" to 0.15f),
        "EDM" to mapOf("Party" to 0.30f, "Energetic" to 0.20f, "Workout" to 0.20f),
        "Dance" to mapOf("Party" to 0.30f, "Happy" to 0.10f, "Energetic" to 0.15f),
        "House" to mapOf("Party" to 0.25f, "Road Trip" to 0.10f),
        "Techno" to mapOf("Party" to 0.15f, "Focus" to 0.10f, "Dark" to 0.10f),
        "Trance" to mapOf("Party" to 0.20f, "Dreamy" to 0.15f, "Energetic" to 0.15f),
        "Drum & Bass" to mapOf("Energetic" to 0.20f, "Workout" to 0.15f),
        "Synthwave" to mapOf("Late Night" to 0.25f, "Dreamy" to 0.20f, "Nostalgia" to 0.15f, "Road Trip" to 0.15f),
        "Electronic" to mapOf("Focus" to 0.10f, "Party" to 0.10f),
        "Disco" to mapOf("Party" to 0.25f, "Happy" to 0.20f, "Nostalgia" to 0.15f),
        "Funk" to mapOf("Party" to 0.15f, "Happy" to 0.15f, "Soulful" to 0.15f),
        "Classical" to mapOf("Calm" to 0.20f, "Focus" to 0.25f, "Epic" to 0.10f, "Emotional" to 0.10f),
        "Soundtrack" to mapOf("Epic" to 0.30f, "Emotional" to 0.10f),
        "Ambient" to mapOf("Calm" to 0.25f, "Sleep" to 0.15f, "Meditation" to 0.25f, "Dreamy" to 0.25f, "Focus" to 0.10f),
        "New Age" to mapOf("Meditation" to 0.30f, "Calm" to 0.25f, "Sleep" to 0.15f, "Dreamy" to 0.15f),
        "Chillout" to mapOf("Calm" to 0.25f, "Late Night" to 0.15f, "Dreamy" to 0.10f),
        "Lo-Fi" to mapOf("Focus" to 0.30f, "Late Night" to 0.25f, "Calm" to 0.20f, "Rainy Day" to 0.10f),
        "Jazz" to mapOf("Soulful" to 0.25f, "Late Night" to 0.20f, "Calm" to 0.10f, "Romantic" to 0.10f),
        "Blues" to mapOf("Soulful" to 0.30f, "Sad" to 0.15f, "Late Night" to 0.15f),
        "Soul" to mapOf("Soulful" to 0.30f, "Romantic" to 0.20f, "Late Night" to 0.10f),
        "R&B" to mapOf("Romantic" to 0.20f, "Late Night" to 0.15f, "Soulful" to 0.20f),
        "Reggae" to mapOf("Happy" to 0.15f, "Calm" to 0.10f, "Road Trip" to 0.10f),
        "Country" to mapOf("Road Trip" to 0.30f, "Nostalgia" to 0.10f),
        "Folk" to mapOf("Calm" to 0.15f, "Sunrise" to 0.15f, "Rainy Day" to 0.10f, "Nostalgia" to 0.10f),
        "Acoustic" to mapOf("Calm" to 0.15f, "Sunrise" to 0.15f, "Rainy Day" to 0.10f),
        "Rock" to mapOf("Road Trip" to 0.20f, "Energetic" to 0.10f, "Motivational" to 0.10f),
        "Alternative" to mapOf("Dreamy" to 0.10f, "Rainy Day" to 0.10f, "Emotional" to 0.10f),
        "Indie" to mapOf("Dreamy" to 0.15f, "Rainy Day" to 0.10f, "Sunrise" to 0.10f),
        "Pop" to mapOf("Happy" to 0.10f, "Party" to 0.05f),
        "K-Pop" to mapOf("Happy" to 0.10f, "Party" to 0.15f, "Energetic" to 0.10f),
        "Devotional" to mapOf("Devotional" to 0.70f, "Meditation" to 0.25f, "Calm" to 0.15f, "Sunrise" to 0.10f),
        "Gospel" to mapOf("Devotional" to 0.35f, "Soulful" to 0.20f, "Emotional" to 0.10f),
        "Ghazal & Sufi" to mapOf("Soulful" to 0.30f, "Emotional" to 0.25f, "Romantic" to 0.20f, "Late Night" to 0.15f, "Sad" to 0.10f),
        "Indian Classical" to mapOf("Meditation" to 0.20f, "Focus" to 0.20f, "Soulful" to 0.20f, "Devotional" to 0.10f),
        "Oldies" to mapOf("Nostalgia" to 0.40f),
        "Tamil Folk" to mapOf("Party" to 0.20f, "Energetic" to 0.20f),
        "Podcast" to mapOf("Focus" to 0.10f),
        "Kids" to mapOf("Happy" to 0.20f)
    )

    private val MOOD_CUES: Map<String, List<String>> = mapOf(
        "Sad" to listOf("sad", "cry", "crying", "tears", "alone", "lonely", "pain", "miss you", "missing you", "broken", "goodbye", "kanneer", "sogam", "pirivu", "judaai", "dard", "tanha", "udaas"),
        "Heartbreak" to listOf("heartbreak", "heartbroken", "broken heart", "breakup", "break up", "bewafa", "judaai", "pirivu", "kanneer", "without you", "forget you", "leave me", "over you"),
        "Romantic" to listOf("love", "kadhal", "kaadhal", "kaathal", "ishq", "pyaar", "pyar", "mohabbat", "prema", "premam", "valentine", "i love you", "kanmani", "sanam", "dil"),
        "Party" to listOf("party", "club", "dance", "dj", "dhol", "kuthu", "naacho", "nacho", "aattam", "mashup", "dance mix"),
        "Workout" to listOf("workout", "gym", "pump", "hustle", "fitness", "training", "run", "running", "cardio"),
        "Beast Mode" to listOf("beast", "monster", "savage", "war", "thunder", "rage", "mass", "villain", "mafia"),
        "Happy" to listOf("happy", "joy", "smile", "sunshine", "feel good", "good vibes", "fun", "sing along", "celebrate"),
        "Rainy Day" to listOf("rain", "rainy", "mazhai", "mazha", "barish", "baarish", "monsoon", "raindrops", "storm"),
        "Late Night" to listOf("night", "midnight", "moon", "after dark", "late night", "insomnia", "nilavu", "raat", "2am", "3am", "moonlight"),
        "Sunrise" to listOf("sunrise", "morning", "good morning", "dawn", "suprabhatam", "subah", "new day", "vidiyal"),
        "Road Trip" to listOf("road", "drive", "driving", "highway", "ride", "journey", "travel", "road trip", "cruise", "roadtrip"),
        "Nostalgia" to listOf("nostalgia", "memories", "memory", "yesterday", "old days", "remember", "throwback", "evergreen", "golden", "oldies"),
        "Dreamy" to listOf("dream", "dreams", "dreamy", "clouds", "stars", "floating", "ethereal"),
        "Sleep" to listOf("sleep", "lullaby", "sleeping", "bedtime", "goodnight", "deep sleep", "thalattu"),
        "Meditation" to listOf("meditation", "mantra", "yoga", "chant", "zen", "healing", "om namah"),
        "Focus" to listOf("study", "focus", "concentration", "deep work", "coding", "work music"),
        "Epic" to listOf("epic", "battle", "hero", "main title", "trailer", "cinematic", "orchestral", "anthem"),
        "Dark" to listOf("dark", "shadow", "shadows", "devil", "hell", "dead", "death", "haunt", "haunting", "nightmare"),
        "Aggressive" to listOf("kill", "rage", "angry", "fight", "riot", "scream", "furious"),
        "Motivational" to listOf("believe", "rise", "win", "champion", "never give up", "hustle", "motivation", "stronger", "fearless", "unstoppable"),
        "Soulful" to listOf("soul", "soulful", "blues", "ghazal"),
        "Devotional" to listOf("devotional", "bhajan", "slokam", "stotram", "suprabhatam", "aarti", "kirtan", "ganesh", "ganapathi", "murugan", "perumal", "amman", "ayyappan", "hanuman", "krishna", "shivan", "venkatesa", "om namah", "jai", "lakshmi", "durga", "saraswati")
    )

    private val OPPOSITES: List<Pair<Set<String>, Set<String>>> = listOf(
        setOf("Energetic", "Workout", "Party", "Aggressive", "Beast Mode") to setOf("Sleep", "Calm", "Meditation", "Dreamy", "Late Night", "Rainy Day"),
        setOf("Happy", "Party", "Sunrise") to setOf("Sad", "Heartbreak", "Dark", "Rainy Day")
    )

    private fun conflicts(chosen: List<String>, m: String): Boolean {
        for ((a, b) in OPPOSITES) {
            if ((m in a && chosen.any { it in b }) || (m in b && chosen.any { it in a })) return true
        }
        return false
    }

    private val GENERIC_DAMP = mapOf("Focus" to 0.35f, "Soulful" to 0.5f, "Romantic" to 0.9f)

    private fun audioMoodScores(i: ProfileInput, s: Sound): Map<String, Float> {
        val e = s.energy; val v = s.valence; val a = s.acoustic; val p = s.pace
        val m = LinkedHashMap<String, Float>()
        m["Energetic"] = 0.75f * e + 0.25f * p
        m["Workout"] = (0.45f * e + 0.35f * high(p, 0.45f, 0.75f) + 0.20f * high(s.bassy, 0.3f, 0.5f)) * (if (p < 0.4f) 0.5f else 1f)
        m["Aggressive"] = 0.45f * high(e, 0.65f, 0.9f) + 0.30f * (1f - v) + 0.25f * low(s.dr, 0.25f, 0.5f)
        m["Party"] = 0.35f * e + 0.30f * mid(p, 0.35f, 0.55f, 0.8f, 1.05f) + 0.35f * v
        m["Happy"] = 0.60f * v + 0.25f * e + 0.15f * p
        m["Sad"] = 0.55f * (1f - v) + 0.30f * (1f - e) + 0.15f * (1f - p)
        m["Calm"] = 0.55f * (1f - e) + 0.25f * a + 0.20f * (1f - p)
        m["Sleep"] = 0.5f * low(e, 0.15f, 0.4f) + 0.3f * low(p, 0.15f, 0.4f) + 0.2f * a
        m["Meditation"] = 0.45f * low(e, 0.2f, 0.45f) + 0.30f * a + 0.25f * low(p, 0.2f, 0.5f)
        m["Focus"] = 0.5f * mid(e, 0.2f, 0.35f, 0.55f, 0.7f) + 0.25f * mid(p, 0.15f, 0.3f, 0.6f, 0.8f) + 0.25f * (1f - kotlin.math.abs(v - 0.5f) * 2f)
        m["Romantic"] = 0.35f * mid(v, 0.3f, 0.45f, 0.7f, 0.85f) + 0.35f * mid(e, 0.15f, 0.25f, 0.55f, 0.7f) + 0.30f * mid(p, 0.1f, 0.25f, 0.55f, 0.75f)
        m["Emotional"] = 0.35f * (1f - v) + 0.30f * high(s.dr, 0.35f, 0.7f) + 0.20f * a + 0.15f * mid(e, 0.2f, 0.3f, 0.6f, 0.8f)
        m["Epic"] = 0.40f * high(s.dr, 0.4f, 0.75f) + 0.30f * high(e, 0.4f, 0.75f) + 0.30f * (1f - a * 0.5f)
        m["Dark"] = 0.45f * (1f - v) + 0.30f * (1f - s.bright) + 0.25f * high(s.bassy, 0.3f, 0.5f)
        m["Motivational"] = 0.40f * e + 0.35f * v + 0.25f * high(p, 0.4f, 0.7f)
        // new moods
        m["Late Night"] = 0.30f * low(e, 0.25f, 0.6f) + 0.25f * mid(p, 0.2f, 0.3f, 0.55f, 0.75f) + 0.25f * mid(v, 0.15f, 0.25f, 0.55f, 0.7f) + 0.20f * (1f - s.bright)
        m["Rainy Day"] = 0.35f * (1f - v) + 0.30f * low(e, 0.2f, 0.55f) + 0.20f * a + 0.15f * low(p, 0.2f, 0.6f)
        m["Road Trip"] = 0.35f * mid(e, 0.45f, 0.6f, 0.85f, 1.05f) + 0.30f * mid(p, 0.4f, 0.5f, 0.8f, 0.95f) + 0.35f * mid(v, 0.45f, 0.6f, 1.0f, 1.2f)
        m["Heartbreak"] = 0.40f * (1f - v) + 0.25f * low(e, 0.2f, 0.65f) + 0.20f * low(p, 0.2f, 0.65f) + 0.15f * a
        m["Sunrise"] = 0.35f * mid(v, 0.5f, 0.65f, 1.0f, 1.2f) + 0.30f * low(e, 0.25f, 0.65f) + 0.20f * a + 0.15f * s.bright
        m["Beast Mode"] = 0.45f * high(e, 0.7f, 0.92f) + 0.25f * high(s.bassy, 0.35f, 0.55f) + 0.20f * low(s.dr, 0.2f, 0.45f) + 0.10f * high(p, 0.5f, 0.8f)
        m["Dreamy"] = 0.20f * high(s.width, 0.5f, 0.9f) + 0.35f * low(e, 0.2f, 0.55f) + 0.25f * (1f - s.bright) + 0.20f * a
        m["Soulful"] = 0.40f * mid(e, 0.25f, 0.35f, 0.65f, 0.8f) + 0.30f * mid(p, 0.15f, 0.3f, 0.65f, 0.85f) + 0.30f * (1f - s.bright)
        m["Nostalgia"] = if (i.year in 1..1999) 0.55f * (1f - (i.year - 1960).coerceAtLeast(0) / 45f).coerceIn(0f, 1f) + 0.2f * a + 0.25f * low(e, 0.3f, 0.7f) else 0f
        m["Devotional"] = 0.0f
        return m
    }

    private fun scoreMoods(i: ProfileInput, s: Sound, text: String, genres: List<String>): Map<String, Float> {
        val audio = if (s.measured) audioMoodScores(i, s) else emptyMap()
        val audioWeight = if (s.measured) 0.75f else 0f
        val baseline = if (s.measured) 0f else 0.30f
        val out = LinkedHashMap<String, Float>()
        for (mood in MoodCatalog.ALL) {
            var prior = 0f
            for (g in genres) prior = max(prior, GENRE_MOOD_PRIOR[g]?.get(mood) ?: 0f)
            val cue = MOOD_CUES[mood]
            val cued = cue != null && hasWord(text, *cue.toTypedArray())
            // Mood that every mid-tempo song "fits" (Focus, Soulful): only believe it with real support.
            val damp = if (prior == 0f && !cued) (GENERIC_DAMP[mood] ?: 1f) else 1f
            var sc = baseline + audioWeight * (audio[mood] ?: 0f) * damp
            sc += prior
            if (cued) sc += 0.30f
            out[mood] = sc
        }
        for (t in i.moodTags) {
            MoodCatalog.canonical(t)?.let { out[it] = (out[it] ?: 0f) + 0.30f }
        }
        for ((k, v) in out) out[k] = clamp(v)
        return out
    }

    /** Old-style single mood guess from a genre, for places that must store one value. */
    fun moodFromGenre(g: String): String = when (g) {
        "Metal", "Rock", "Punk", "Hip-Hop", "Phonk" -> "Energetic"
        "EDM", "Dance", "House", "Techno", "Trance", "Electronic", "Disco" -> "Party"
        "Classical", "Ambient", "Lo-Fi", "Instrumental", "Jazz", "Chillout", "New Age" -> "Calm"
        "Devotional", "Gospel" -> "Devotional"
        "Blues", "Soul", "R&B", "Ghazal & Sufi" -> "Soulful"
        "Podcast", "Audiobook", "Spoken Word" -> "Focus"
        "Soundtrack" -> "Epic"
        else -> "Happy"
    }

    /** Empty when the sound was never measured and nothing else (genre, title words, tags) says anything. */
    private fun pickMoods(scores: Map<String, Float>, measured: Boolean): List<String> {
        val ranked = scores.entries.sortedByDescending { it.value }
        val chosen = ArrayList<String>()
        val top = ranked.first().value
        for ((mood, sc) in ranked) {
            if (chosen.size >= 4) break
            if (sc < 0.45f) break
            if (chosen.isNotEmpty() && sc < top - 0.14f) break
            if (conflicts(chosen, mood)) continue
            chosen += mood
        }
        if (chosen.isEmpty() && measured) chosen += ranked.first().key
        return chosen
    }

    // ------------------------------------------------------------------ styles
    private fun pickStyles(i: ProfileInput, s: Sound, text: String, genres: List<String>, language: String?, moods: List<String>): List<String> {
        val c = LinkedHashMap<String, Float>()
        fun add(name: String, score: Float) { c[name] = max(c[name] ?: 0f, score) }
        val fam = genres.map { GenreTaxonomy.familyOf(it) }.toSet()
        val isInstrumental = "Instrumental" in genres || hasWord(text, "instrumental", "karaoke")
        val devotional = "Devotional" in genres || hasWord(text, "devotional", "bhajan", "slokam", "stotram", "suprabhatam", "aarti", "kirtan")
        val regional = language != null && language in LanguageSense.INDIAN

        if (regional) {
            if (devotional) add("${language!!} Devotional", 0.9f)
            val dancey = hasWord(text, "kuthu", "dhol", "bhangra", "dance", "naacho", "nacho")
            if (s.measured) {
                if (!devotional && s.energy < 0.55f && s.pace < 0.62f && !isInstrumental) add("$language Melody", 0.62f + (0.55f - s.energy) * 0.5f)
                if (s.energy >= 0.68f) add("$language Mass Beats", 0.6f + (s.energy - 0.68f))
                if (i.tempoBpm in 112f..160f && s.bassy >= 0.3f && s.energy > 0.55f) {
                    add(when (language) { "Tamil" -> "Tamil Kuthu"; "Hindi" -> "Bollywood Dance"; "Punjabi" -> "Bhangra Beats"; else -> "$language Dance Beats" }, 0.8f)
                }
            }
            if (dancey) add(when (language) { "Tamil" -> "Tamil Kuthu"; "Hindi" -> "Bollywood Dance"; "Punjabi" -> "Bhangra Beats"; else -> "$language Dance Beats" }, 0.75f)
        }

        val unplugged = hasWord(text, "acoustic", "unplugged")
        if (s.measured) {
            if (s.bassy >= 0.7f && s.loud >= 0.55f) add("Bass Boosted", 0.6f + 0.3f * (s.bassy - 0.7f) / 0.3f)
            if (s.pace in 0.45f..1f && s.energy >= 0.6f && s.valence >= 0.45f && (fam.contains("Dance") || fam.contains("Electronic") || s.bassy >= 0.3f)) add("Dance Floor", 0.7f)
            if (s.energy in 0.25f..0.6f && s.pace in 0.25f..0.7f && s.bright < 0.5f && fam.any { it in setOf("R&B", "Electronic", "Chill", "Pop", "Hip-Hop", "Jazz") }) add("Late Night Drive", 0.65f)
            val notAcousticStyle = fam.any { it in setOf("Classical", "Chill", "Instrumental", "Soundtrack", "Spiritual", "Electronic") }
            if ((s.acoustic >= 0.62f && s.energy <= 0.5f && !isInstrumental && !notAcousticStyle) || unplugged) add("Acoustic Unplugged", 0.75f)
            if (("Soundtrack" in genres || "Classical" in genres) && s.dr >= 0.5f && s.energy >= 0.4f) add("Cinematic Epic", 0.75f)
            if (s.pace <= 0.4f && s.valence <= 0.5f && s.energy <= 0.5f && !isInstrumental && !devotional && ("Sad" in moods || "Romantic" in moods || "Heartbreak" in moods || "Emotional" in moods)) add("Melodic Ballads", 0.65f)
            if (fam.contains("Pop") && s.valence >= 0.55f && s.energy in 0.45f..0.8f) add("Feel-Good Pop", 0.7f)
            if (s.energy >= 0.82f && s.dr < 0.35f && s.bright >= 0.4f) add("High Voltage", 0.72f)
            if (s.energy <= 0.3f && (s.width >= 0.6f || fam.contains("Chill")) && !devotional) add("Soft & Dreamy", 0.66f)
            if (("Lo-Fi" in genres || isInstrumental) && s.energy in 0.2f..0.55f) add("Study Beats", 0.78f)
            if (genres.any { it == "Jazz" || it == "Blues" || it == "Soul" } && s.energy <= 0.6f) add("Jazz Lounge", 0.72f)
            if (genres.any { it == "Hip-Hop" || it == "Phonk" } && s.bassy >= 0.5f) add("Trap & Bass", 0.72f)
            if (fam.contains("Rock") && s.energy >= 0.6f) add("Rock Anthems", 0.66f)
        } else {
            if ("Lo-Fi" in genres) add("Study Beats", 0.7f)
            if (genres.any { it == "Jazz" || it == "Blues" || it == "Soul" }) add("Jazz Lounge", 0.7f)
            if ("Soundtrack" in genres) add("Cinematic Epic", 0.7f)
            if (genres.any { it == "Hip-Hop" || it == "Phonk" }) add("Trap & Bass", 0.6f)
            if (unplugged) add("Acoustic Unplugged", 0.75f)
        }
        if ((i.year in 1..1994) || "Oldies" in genres) add("Retro Classics", 0.7f)

        return c.entries.sortedByDescending { it.value }.filter { it.value >= 0.6f }.take(2).map { it.key }
    }

    // ------------------------------------------------------------------ main entry
    fun profile(i: ProfileInput): SongProfile {
        val s = soundOf(i)
        val text = textOf(i)
        val language = LanguageSense.detect(i.title, i.artist, i.album, i.folder, i.tagGenre)
            ?: i.language?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
        val genres = pickGenres(scoreGenres(i, s, genreTextOf(i), language.takeIf { it != "English" }))
        val moodScores = scoreMoods(i, s, text, genres)
        val moods = pickMoods(moodScores, s.measured)
        val styles = pickStyles(i, s, text, genres, language, moods)
        return SongProfile(genres, styles, moods, moodScores, language, s.energy, s.valence)
    }
}
