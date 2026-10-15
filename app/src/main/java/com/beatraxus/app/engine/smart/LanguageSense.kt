package com.beatraxus.app.engine.smart


/**
 * Finds the language of a song from its text (title, artist, album, folder, tags): first by the
 * script it is written in, then by well-known words / artists written in Latin letters.
 * Returns null when there is no clear signal (callers treat that as "unknown", not English).
 */
object LanguageSense {

    val REGIONAL_FILM = setOf("Tamil", "Hindi", "Telugu", "Malayalam", "Kannada")
    val INDIAN = REGIONAL_FILM + setOf("Punjabi", "Bengali", "Gujarati", "Marathi")

    private fun scriptOf(cp: Int): String? = when (cp) {
        in 0x0B80..0x0BFF -> "Tamil"
        in 0x0C00..0x0C7F -> "Telugu"
        in 0x0C80..0x0CFF -> "Kannada"
        in 0x0D00..0x0D7F -> "Malayalam"
        in 0x0900..0x097F -> "Hindi"
        in 0x0980..0x09FF -> "Bengali"
        in 0x0A00..0x0A7F -> "Punjabi"
        in 0x0A80..0x0AFF -> "Gujarati"
        in 0x0600..0x06FF -> "Arabic"
        in 0xAC00..0xD7AF, in 0x1100..0x11FF -> "Korean"
        in 0x3040..0x30FF -> "Japanese"
        in 0x4E00..0x9FFF -> "Chinese"
        in 0x0400..0x04FF -> "Russian"
        in 0x0E00..0x0E7F -> "Thai"
        else -> null
    }

    // Words / names that are (almost) only used in one language. STRONG = one hit is enough,
    // WEAK = common romanised words, two different hits are needed.
    private val STRONG: Map<String, List<String>> = mapOf(
        "Tamil" to listOf("tamil", "tamizh", "thamizh", "kollywood", "ilaiyaraaja", "ilayaraja", "yuvan shankar raja",
            "anirudh", "harris jayaraj", "d imman", "gv prakash", "g v prakash", "santhosh narayanan", "sid sriram",
            "hiphop tamizha", "sam c s", "vijay antony", "haricharan", "thalapathy", "thalaivar", "kuthu"),
        "Hindi" to listOf("hindi", "bollywood", "arijit singh", "atif aslam", "pritam", "shreya ghoshal", "badshah",
            "honey singh", "neha kakkar", "jubin nautiyal", "armaan malik", "kumar sanu", "udit narayan", "alka yagnik",
            "lata mangeshkar", "kishore kumar", "mohammed rafi", "asha bhosle", "sonu nigam", "vishal shekhar",
            "shankar ehsaan loy", "darshan raval", "b praak", "tulsi kumar", "sunidhi chauhan"),
        "Telugu" to listOf("telugu", "tollywood", "devi sri prasad", "s thaman", "thaman s", "mm keeravani", "ghantasala"),
        "Malayalam" to listOf("malayalam", "mollywood", "gopi sundar", "shaan rahman", "kj yesudas", "k j yesudas"),
        "Kannada" to listOf("kannada", "sandalwood", "arjun janya"),
        "Punjabi" to listOf("punjabi", "bhangra", "diljit dosanjh", "sidhu moose wala", "ap dhillon", "karan aujla",
            "guru randhawa", "ammy virk", "jass manak", "babbu maan", "gippy grewal", "harrdy sandhu")
    )

    private val WEAK: Map<String, List<String>> = mapOf(
        "Tamil" to listOf("kadhal", "kaadhal", "kaathal", "kanmani", "nenjam", "nenjukkul", "uyire", "uyir", "vaanam",
            "vaanil", "ennai", "unnai", "ponnu", "penne", "kannae", "kanne", "kannamma", "chellam", "anbe", "anbu",
            "mazhai", "megam", "thendral", "paadal", "paattu", "vennila", "nilavu", "naan", "neeya", "thalattu", "sogam", "pirivu", "kanneer"),
        "Hindi" to listOf("pyaar", "pyar", "dil", "ishq", "mohabbat", "tera", "tere", "mera", "tum", "hum", "zindagi",
            "sanam", "dilbar", "jaan", "yaar", "kabhi", "mujhe", "teri", "meri", "saathiya", "aankhon", "tujhe", "raat",
            "baarish", "barish", "judaai", "bewafa", "tanha", "dard"),
        "Telugu" to listOf("nenu", "neeku", "prema", "nuvvu", "nuvve", "chinnadi", "buttabomma", "naa", "ninnu", "manasu"),
        "Malayalam" to listOf("ente", "ormakal", "premam", "mazha", "ninakku", "pennu", "kannil"),
        "Kannada" to listOf("neenu", "nanna", "preethi", "hrudaya", "kannada"),
        "Punjabi" to listOf("kudi", "munda", "jatt", "gabru", "pind", "sohni", "veham")
    )

    private fun words(text: String): String = " " + GenreTaxonomy.key(text) + " "

    /** @return the language name ("Tamil", "Hindi", ...) or null when unsure. */
    fun detect(vararg texts: String?): String? {
        val joined = texts.filterNotNull().joinToString(" ")
        if (joined.isBlank()) return null

        // 1. script
        val counts = HashMap<String, Int>()
        joined.codePoints().forEach { cp ->
            scriptOf(cp)?.let { counts.merge(it, 1, Int::plus) }
        }
        if (counts.isNotEmpty()) {
            // Japanese text mixes kana with Han characters: kana wins.
            if ((counts["Japanese"] ?: 0) > 0) counts.remove("Chinese")
            val best = counts.maxByOrNull { it.value }
            if (best != null && best.value >= 2) return best.key
        }

        // 2. well-known words / artists in Latin letters
        val padded = words(joined)
        val points = HashMap<String, Int>()
        for ((lang, list) in STRONG) {
            if (list.any { padded.contains(" ${GenreTaxonomy.key(it)} ") }) points.merge(lang, 2, Int::plus)
        }
        for ((lang, list) in WEAK) {
            val hits = list.count { padded.contains(" ${GenreTaxonomy.key(it)} ") }
            if (hits > 0) points.merge(lang, hits, Int::plus)
        }
        val ranked = points.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        if (top.value < 2) return null
        if (ranked.size > 1 && ranked[1].value == top.value) return null
        return top.key
    }

    fun isRegionalFilm(language: String?): Boolean = language != null && language in REGIONAL_FILM

}
