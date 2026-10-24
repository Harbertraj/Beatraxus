package com.beatraxus.app.model

/**
 * Pure helpers for lyric translation: language list, request batching and answer splitting.
 * The network call lives in repository/lyrics/LyricsTranslator.kt.
 */
object LyricsTranslationLogic {

    data class Language(val tag: String, val name: String)

    val LANGUAGES: List<Language> = listOf(
        Language("en", "English"), Language("ta", "தமிழ் (Tamil)"), Language("hi", "हिन्दी (Hindi)"),
        Language("te", "తెలుగు (Telugu)"), Language("ml", "മലയാളം (Malayalam)"), Language("kn", "ಕನ್ನಡ (Kannada)"),
        Language("bn", "বাংলা (Bengali)"), Language("mr", "मराठी (Marathi)"), Language("pa", "ਪੰਜਾਬੀ (Punjabi)"),
        Language("ur", "اردو (Urdu)"), Language("es", "Español"), Language("fr", "Français"),
        Language("de", "Deutsch"), Language("pt", "Português"), Language("ru", "Русский"),
        Language("ja", "日本語"), Language("ko", "한국어"), Language("zh-CN", "中文 (简体)"),
        Language("zh-TW", "中文 (繁體)"), Language("ar", "العربية"), Language("id", "Bahasa Indonesia"),
        Language("tr", "Türkçe"), Language("it", "Italiano")
    )

    const val MAX_BATCH_CHARS = 3_500
    private const val SEPARATOR_COST = 8

    /** Private-use markers survive machine translation and tell us where one line ends. */
    fun marker(index: Int): String = "" + index.toString().padStart(4, '0') + ""

    val markerRegex = Regex("\\uE000[^\\uE001]*\\uE001")

    /** Splits line texts into batches of at most [MAX_BATCH_CHARS] characters (never splits a line). */
    fun batches(texts: List<String>, maxChars: Int = MAX_BATCH_CHARS): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var length = 0
        texts.forEachIndexed { index, text ->
            val cost = text.length + if (current.isEmpty()) 0 else SEPARATOR_COST
            if (current.isNotEmpty() && length + cost > maxChars) {
                result += current
                current = mutableListOf()
                length = 0
            }
            length += text.length + if (current.isEmpty()) 0 else SEPARATOR_COST
            current += index
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** The single string sent for one batch: lines joined by numbered markers. */
    fun payload(texts: List<String>, indexes: List<Int>): String = buildString {
        indexes.forEachIndexed { n, lineIndex ->
            if (n > 0) append('\n').append(marker(n)).append('\n')
            append(texts[lineIndex])
        }
    }

    /** Splits the translated body back into [expected] parts; null when the count does not match. */
    fun split(translatedBody: String, expected: Int): List<String>? {
        val parts = translatedBody.split(markerRegex).map { it.trim() }
        return if (parts.size == expected && parts.none { it.isBlank() }) parts else null
    }

    /** Lines that should be translated: not blank, not a bare section header like "[Chorus]". */
    fun translatable(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("[") && t.endsWith("]")) return false
        // music-note / dash-only filler lines
        return t.any { it.isLetter() }
    }

    fun cacheKey(trackId: String, target: String, texts: List<String>): String {
        var h = 1125899906842597L
        for (t in texts) for (c in t) h = 31 * h + c.code
        return "${trackId.hashCode().toUInt()}_${target}_${texts.size}_${h.toULong()}"
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
    }

    fun sameLanguage(source: String, target: String): Boolean {
        fun base(t: String) = t.lowercase().substringBefore('-').substringBefore('_')
        val s = source.trim(); val t = target.trim()
        if (s.isEmpty() || t.isEmpty()) return false
        // zh-CN and zh-TW are different scripts: equal only when the full tag matches (or the source has no region).
        if (base(s) == "zh" && base(t) == "zh") return s.equals(t, ignoreCase = true) || !s.contains('-')
        return base(s) == base(t)
    }
}
