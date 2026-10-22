package com.beatraxus.app.subtitles.domain

import java.util.Locale

/** A subtitle file found next to a video, with what its file name tells us about it. */
data class SiblingSubtitleCandidate(
    val fileName: String,
    /** Lower-case, no dot: "srt", "vtt", "ass" or "ssa". */
    val extension: String,
    /** Two-letter language code read from the name ("en" for "Movie.eng.srt"), or null when the name has none. */
    val languageCode: String?,
    val isForced: Boolean,
    val isHearingImpaired: Boolean,
    val score: Int
)

/**
 * Video feature 21: pick the subtitle files that belong to a video from the file names in its folder.
 *
 * The rule is deliberately strict. A file belongs to the video only when its name is the video's name plus a tail
 * that is made entirely of things we recognise (a language, "forced", "sdh", ...). `Movie.mkv` therefore matches
 * `Movie.srt`, `Movie.en.srt` and `Movie_English.forced.srt`, but not `Movie 2.srt` or `Movie Trailer.srt`. A
 * wrong subtitle is worse than none, and the subtitle sheet is always there for a manual choice.
 *
 * Pure Kotlin, no Android types, so it can be unit tested.
 */
object SubtitleSiblingMatcher {

    /**
     * Formats [SubtitleConverter] can turn into SRT. `.sub` is left out on purpose: it is MicroDVD or VobSub,
     * which the converter rejects, so "finding" one would only end in an error.
     */
    val SUPPORTED_EXTENSIONS: List<String> = listOf("srt", "vtt", "ass", "ssa")

    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isSubtitleFileName(fileName: String): Boolean = extensionOf(fileName) in SUPPORTED_EXTENSIONS

    /** The video's name without its extension. A trailing part that cannot be an extension ("Movie.2020") stays. */
    fun stemOf(videoFileName: String): String {
        val dot = videoFileName.lastIndexOf('.')
        if (dot <= 0) return videoFileName
        val ext = videoFileName.substring(dot + 1)
        val looksLikeExtension = ext.length in 1..5 && ext.all { it.isLetterOrDigit() } && ext.any { it.isLetter() }
        return if (looksLikeExtension) videoFileName.substring(0, dot) else videoFileName
    }

    /** "en", "eng", "English" and "en-US" all become "en". Unknown values come back lower-cased, or null if blank. */
    fun normalizeLanguage(value: String): String? {
        val v = value.trim().lowercase(Locale.ROOT)
        if (v.isEmpty()) return null
        val base = v.substringBefore('-').substringBefore('_')
        return LANGUAGES[base] ?: LANGUAGES[v] ?: base
    }

    /**
     * Every subtitle in [fileNames] that belongs to [videoFileName], best first.
     *
     * Order: an exact name match beats a name with a language tail; a preferred language ([preferredLanguages],
     * best first) beats the rest; a tagged language that is not preferred is demoted below an untagged file;
     * forced-only and hearing-impaired versions rank lower; `.srt` is preferred over the other formats. Ties are
     * broken by file name so the result never depends on the order the folder was listed in.
     */
    fun rank(
        videoFileName: String,
        fileNames: List<String>,
        preferredLanguages: List<String> = emptyList()
    ): List<SiblingSubtitleCandidate> {
        val videoStem = stemOf(videoFileName)
        if (videoStem.isBlank()) return emptyList()
        val preferred = preferredLanguages.mapNotNull { normalizeLanguage(it) }

        val out = ArrayList<SiblingSubtitleCandidate>()
        for (name in fileNames) {
            val ext = extensionOf(name) ?: continue
            if (ext !in SUPPORTED_EXTENSIONS) continue
            val stem = name.substring(0, name.length - ext.length - 1)
            if (stem.length < videoStem.length) continue
            if (!stem.regionMatches(0, videoStem, 0, videoStem.length, ignoreCase = true)) continue

            val remainder = stem.substring(videoStem.length)
            // "Movie2.srt" and "Movieland.srt" are other videos' files: the tail must start at a separator.
            if (remainder.isNotEmpty() && remainder[0].isLetterOrDigit()) continue
            val tail = if (remainder.isEmpty()) EMPTY_TAIL else (parseTail(remainder) ?: continue)

            var score = if (remainder.isEmpty()) 1000 else 800
            if (tail.forced) score -= 300
            if (tail.hearingImpaired) score -= 50
            val lang = tail.language
            if (lang != null && preferred.isNotEmpty()) {
                val idx = preferred.indexOf(lang)
                score += if (idx >= 0) 250 - minOf(idx, 5) * 20 else -150
            }
            score += when (ext) {
                "srt" -> 20
                "vtt" -> 10
                else -> 0
            }
            out.add(SiblingSubtitleCandidate(name, ext, lang, tail.forced, tail.hearingImpaired, score))
        }
        return out.sortedWith(compareByDescending<SiblingSubtitleCandidate> { it.score }.thenBy { it.fileName.lowercase(Locale.ROOT) })
    }

    /** The subtitle to load on its own: the best match that is not a forced-only track. */
    fun pickForAutoLoad(
        videoFileName: String,
        fileNames: List<String>,
        preferredLanguages: List<String> = emptyList()
    ): SiblingSubtitleCandidate? = rank(videoFileName, fileNames, preferredLanguages).firstOrNull { !it.isForced }

    // ---- tail parsing ----

    private class Tail(val language: String?, val forced: Boolean, val hearingImpaired: Boolean)

    private val EMPTY_TAIL = Tail(null, forced = false, hearingImpaired = false)
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val UTF_TAG = Regex("(?i)utf[-_ ]?(8|16)(le|be)?")
    private val HEARING_IMPAIRED_WORDS = setOf("sdh", "cc", "hoh", "hearing", "impaired")
    private val NOISE_WORDS = setOf("sub", "subs", "subtitle", "subtitles", "default", "full", "unicode", "text")

    /** Reads what follows the video's name ("en.forced", "_English"). Null when any part is not recognised. */
    private fun parseTail(tail: String): Tail? {
        val tokens = tail.replace(UTF_TAG, " ").split(NON_WORD).filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }
        var language: String? = null
        var forced = false
        var hearingImpaired = false
        var sawHi = false
        for (t in tokens) {
            when {
                t == "forced" -> forced = true
                t in HEARING_IMPAIRED_WORDS -> hearingImpaired = true
                t == "hi" -> sawHi = true
                t in NOISE_WORDS -> Unit
                else -> {
                    val lang = LANGUAGES[t] ?: return null
                    if (language == null) language = lang
                }
            }
        }
        // "hi" is Hindi on its own ("Movie.hi.srt") but "hearing impaired" next to a language ("Movie.en.hi.srt").
        if (sawHi) {
            if (language == null) language = "hi" else hearingImpaired = true
        }
        return Tail(language, forced, hearingImpaired)
    }

    // ---- languages ----

    private val LANGUAGES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) {
            put(code, code)
            names.forEach { put(it, code) }
        }
        add("en", "eng", "english")
        add("hi", "hin", "hindi")
        add("ta", "tam", "tamil")
        add("te", "tel", "telugu")
        add("ml", "mal", "malayalam")
        add("kn", "kan", "kannada")
        add("bn", "ben", "bengali", "bangla")
        add("mr", "mar", "marathi")
        add("gu", "guj", "gujarati")
        add("pa", "pan", "punjabi", "panjabi")
        add("ur", "urd", "urdu")
        add("ne", "nep", "nepali")
        add("si", "sin", "sinhala", "sinhalese")
        add("ja", "jpn", "japanese")
        add("ko", "kor", "korean")
        add("zh", "chi", "zho", "chinese", "chs", "cht", "zhs", "zht", "cn", "tw", "hans", "hant", "simplified", "traditional", "mandarin", "cantonese")
        add("es", "spa", "esp", "spanish")
        add("fr", "fre", "fra", "french")
        add("de", "ger", "deu", "german")
        add("pt", "por", "pob", "br", "brazilian", "portuguese")
        add("ru", "rus", "russian")
        add("ar", "ara", "arabic")
        add("tr", "tur", "turkish")
        add("it", "ita", "italian")
        add("nl", "dut", "nld", "dutch")
        add("pl", "pol", "polish")
        add("sv", "swe", "swedish")
        add("da", "dan", "danish")
        add("no", "nor", "nob", "nb", "norwegian")
        add("fi", "fin", "finnish")
        add("el", "gre", "ell", "greek")
        add("he", "heb", "hebrew")
        add("th", "tha", "thai")
        add("vi", "vie", "vietnamese")
        add("id", "ind", "indonesian")
        add("ms", "may", "msa", "malay")
        add("cs", "cze", "ces", "czech")
        add("hu", "hun", "hungarian")
        add("ro", "rum", "ron", "romanian")
        add("bg", "bul", "bulgarian")
        add("uk", "ukr", "ukrainian")
        add("fa", "per", "fas", "persian", "farsi")
        add("sr", "srp", "serbian")
        add("hr", "hrv", "croatian")
        add("sk", "slo", "slk", "slovak")
        add("sl", "slv", "slovenian")
    }
}
