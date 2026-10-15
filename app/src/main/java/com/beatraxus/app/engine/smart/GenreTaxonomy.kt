package com.beatraxus.app.engine.smart

import java.util.Locale

/**
 * Clean genre vocabulary for the library. Embedded genre tags are a mess ("Pop/Rock", "hip hop",
 * "(13)", "Bollywood", "Unknown", "Other", "R&B;Soul" ...), so the same music ends up scattered over
 * dozens of groups. This turns any raw tag into ONE spelling per genre and splits multi-value tags.
 *
 * Pure Kotlin (no Android classes) so it can be unit tested on the JVM.
 */
object GenreTaxonomy {

    const val UNKNOWN = "Unknown"

    private class Def(val name: String, val family: String, val aliases: List<String>)

    private val DEFS: List<Def> = listOf(
        // ---- Pop
        Def("Pop", "Pop", listOf("pop music", "synthpop", "synth pop", "dance pop", "electropop", "teen pop", "dream pop", "top 40", "chart", "hits", "vocal pop", "adult contemporary", "soft pop")),
        Def("K-Pop", "Pop", listOf("kpop", "k pop", "korean pop", "korean", "k-pop", "korean music")),
        Def("J-Pop", "Pop", listOf("jpop", "j pop", "japanese pop", "japanese", "anime", "city pop", "j-pop", "j rock", "jrock")),
        Def("Indian Pop", "Pop", listOf("indipop", "indi pop", "indian pop", "hindi pop", "desi pop", "tamil pop", "telugu pop", "indian indie")),
        // ---- Rock family
        Def("Rock", "Rock", listOf("classic rock", "hard rock", "soft rock", "folk rock", "southern rock", "progressive rock", "prog rock", "psychedelic rock", "psychedelic", "psychadelic", "rock and roll", "rock n roll", "art rock", "glam rock", "blues rock", "garage rock", "instrumental rock", "post rock", "math rock", "surf rock", "rockabilly", "arena rock", "pop rock", "rock music")),
        Def("Alternative", "Rock", listOf("alternative rock", "alt rock", "alternrock", "alt", "grunge", "post grunge", "emo", "shoegaze", "alternative and punk", "new wave", "britpop")),
        Def("Indie", "Rock", listOf("indie rock", "indie folk", "indie pop", "bedroom pop", "indie music", "independent", "lo fi indie")),
        Def("Punk", "Rock", listOf("punk rock", "pop punk", "hardcore punk", "acid punk", "ska punk", "post punk", "hardcore")),
        Def("Metal", "Metal", listOf("heavy metal", "thrash metal", "death metal", "black metal", "doom metal", "power metal", "nu metal", "metalcore", "deathcore", "progressive metal", "hard and heavy", "industrial metal", "symphonic metal", "folk metal")),
        // ---- Hip-hop
        Def("Hip-Hop", "Hip-Hop", listOf("hip hop", "hiphop", "rap", "hip hop rap", "gangsta", "gangsta rap", "trap", "drill", "boom bap", "conscious hip hop", "christian rap", "east coast", "west coast", "cloud rap", "mumble rap", "desi hip hop", "tamil hip hop", "hip hop and rap", "rap and hip hop", "urban")),
        Def("Phonk", "Hip-Hop", listOf("phonk", "drift phonk", "memphis phonk")),
        // ---- R&B / soul / funk
        Def("R&B", "R&B", listOf("rnb", "r and b", "rhythm and blues", "contemporary r and b", "contemporary rnb", "alternative r and b", "alternative rnb")),
        Def("Soul", "R&B", listOf("neo soul", "motown", "northern soul", "soul music", "soul and r and b")),
        Def("Funk", "R&B", listOf("p funk", "jazz funk", "funk music", "g funk")),
        Def("Disco", "Dance", listOf("nu disco", "italo disco", "disco music")),
        // ---- Electronic / dance
        Def("Electronic", "Electronic", listOf("electronica", "electro", "idm", "electronic music", "glitch", "future garage", "uk garage", "garage", "breakbeat", "synth")),
        Def("EDM", "Electronic", listOf("electronic dance music", "big room", "future bass", "hardstyle", "dubstep", "brostep", "electro house", "dance and electronic", "dance electronic", "edm music", "progressive electro", "festival")),
        Def("Dance", "Dance", listOf("dance music", "club", "eurodance", "euro dance", "euro techno", "rave", "dancefloor", "dance pop music")),
        Def("House", "Electronic", listOf("deep house", "tech house", "progressive house", "afro house", "tropical house", "house music", "funky house", "acid house")),
        Def("Techno", "Electronic", listOf("minimal techno", "industrial", "ebm", "techno industrial", "acid", "techno music", "detroit techno")),
        Def("Trance", "Electronic", listOf("psytrance", "goa", "goa trance", "progressive trance", "uplifting trance", "trance music")),
        Def("Drum & Bass", "Electronic", listOf("drum and bass", "dnb", "drum n bass", "jungle", "d and b", "liquid dnb")),
        Def("Synthwave", "Electronic", listOf("retrowave", "outrun", "vaporwave", "chillwave", "future funk", "darkwave", "synth wave")),
        // ---- Chill
        Def("Lo-Fi", "Chill", listOf("lo fi", "lofi", "lofi hip hop", "chillhop", "lo fi hip hop", "lofi beats", "lo fi beats")),
        Def("Chillout", "Chill", listOf("chill", "chill out", "lounge", "easy listening", "relaxation", "relaxing", "downtempo", "trip hop", "triphop", "chill hop", "mellow", "smooth")),
        Def("Ambient", "Chill", listOf("drone", "space", "dark ambient", "atmospheric", "soundscape", "soundscapes", "ambient music", "dream")),
        Def("New Age", "Chill", listOf("meditative", "meditation", "healing", "spa", "yoga", "nature sounds", "binaural", "relaxation and meditation", "zen")),
        // ---- Jazz / blues / classical
        Def("Jazz", "Jazz", listOf("smooth jazz", "acid jazz", "bebop", "swing", "big band", "vocal jazz", "fusion", "jazz fusion", "bossa nova", "cool jazz", "latin jazz", "jazz music", "contemporary jazz")),
        Def("Blues", "Blues", listOf("delta blues", "electric blues", "blues music", "chicago blues")),
        Def("Classical", "Classical", listOf("orchestral", "symphony", "baroque", "opera", "chamber", "chamber music", "piano classical", "contemporary classical", "modern classical", "neoclassical", "neo classical", "romantic era", "classical music", "concerto", "sonata", "piano")),
        Def("Soundtrack", "Soundtrack", listOf("ost", "film score", "movie score", "score", "original soundtrack", "game soundtrack", "video game", "game", "anime ost", "trailer", "cinematic", "musical", "musicals", "showtunes", "bgm", "theme", "movie soundtrack", "motion picture soundtrack", "film music", "background score")),
        Def("Instrumental", "Instrumental", listOf("karaoke", "instrumental pop", "background music", "instrumentals", "backing track", "instrumental music")),
        // ---- Country / folk / acoustic
        Def("Country", "Country", listOf("bluegrass", "americana", "outlaw country", "country pop", "western", "honky tonk", "country and western", "country music", "alt country")),
        Def("Folk", "Folk", listOf("traditional", "folk music", "singer songwriter", "singer-songwriter", "contemporary folk", "folklore")),
        Def("Acoustic", "Folk", listOf("unplugged", "acoustic pop", "acoustic guitar")),
        // ---- Reggae / latin / world
        Def("Reggae", "Reggae", listOf("ska", "dub", "roots reggae", "reggae music", "reggae fusion")),
        Def("Dancehall", "Reggae", listOf("ragga", "dancehall music")),
        Def("Latin", "World", listOf("salsa", "bachata", "reggaeton", "merengue", "cumbia", "tango", "flamenco", "latin pop", "latino", "latin music", "regional mexican", "spanish")),
        Def("Afrobeats", "World", listOf("afrobeat", "afropop", "amapiano", "afro", "afro pop", "african", "highlife")),
        Def("World", "World", listOf("world music", "global", "ethnic", "tribal", "celtic", "native american", "arabic", "turkish", "worldbeat", "international")),
        // ---- Spiritual
        Def("Gospel", "Spiritual", listOf("christian", "ccm", "worship", "praise", "contemporary christian", "christian and gospel", "christian music", "gospel music", "praise and worship", "tamil christian", "christian songs")),
        Def("Devotional", "Spiritual", listOf("bhajan", "bhajans", "bhakti", "kirtan", "aarti", "slokam", "stotram", "mantra", "mantras", "chants", "chant", "religious", "spiritual", "hymn", "hymns", "islamic", "nasheed", "naat", "gurbani", "shabad", "devotional songs", "tamil devotional", "hindu devotional", "carnatic devotional", "suprabhatam", "ayyappan", "murugan", "amman")),
        // ---- Indian regional
        Def("Tamil Film Music", "Indian", listOf("tamil", "tamil film", "tamil movie", "tamil songs", "kollywood", "tamil cinema", "tamil melody", "tamil mass", "tamil film songs", "tamil film hits", "tamil hits", "tamil music", "tamil movie songs", "tamil mp3")),
        Def("Tamil Folk", "Indian", listOf("gaana", "gana", "naattupura paadal", "villu paattu", "tamil folk", "kuthu", "tamil kuthu")),
        Def("Hindi Film Music", "Indian", listOf("hindi", "hindi film", "bollywood", "filmi", "hindi songs", "hindi movie", "hindi melody", "hindi film songs", "bollywood songs", "hindi music", "hindi hits", "bollywood hits", "bollywood music", "hindi romantic")),
        Def("Telugu Film Music", "Indian", listOf("telugu", "tollywood", "telugu film", "telugu songs", "telugu movie", "telugu music", "telugu hits", "telugu film songs")),
        Def("Malayalam Film Music", "Indian", listOf("malayalam", "mollywood", "malayalam film", "malayalam songs", "malayalam movie", "malayalam music", "malayalam hits")),
        Def("Kannada Film Music", "Indian", listOf("kannada", "sandalwood", "kannada film", "kannada songs", "kannada movie", "kannada music", "kannada hits")),
        Def("Punjabi", "Indian", listOf("bhangra", "punjabi pop", "punjabi songs", "punjabi music", "punjabi hits", "punjabi folk")),
        Def("Bengali", "Indian", listOf("rabindra sangeet", "bengali songs", "bengali music", "bangla")),
        Def("Indian Classical", "Indian", listOf("carnatic", "hindustani", "raga", "karnatic", "classical indian", "indian classical music", "carnatic classical", "hindustani classical", "thumri", "dhrupad")),
        Def("Ghazal & Sufi", "Indian", listOf("ghazal", "sufi", "qawwali", "sufi rock", "ghazals", "sufi music", "urdu", "nazm")),
        // ---- Spoken & misc
        Def("Audiobook", "Spoken", listOf("audio book", "audiobooks", "audio books", "books")),
        Def("Podcast", "Spoken", listOf("podcasts", "news", "talk", "education", "interview", "talk radio", "radio show")),
        Def("Spoken Word", "Spoken", listOf("speech", "comedy", "pranks", "sound clip", "spoken", "stand up", "poetry", "lecture")),
        Def("Oldies", "Retro", listOf("retro", "vintage", "classic hits", "golden oldies", "evergreen", "golden era", "oldies but goodies", "old songs", "old is gold")),
        Def("Kids", "Family", listOf("children", "childrens", "children s music", "kids music", "nursery", "nursery rhymes", "lullaby", "lullabies", "kids songs", "baby")),
        Def("Christmas", "Seasonal", listOf("holiday", "xmas", "christmas music", "christmas songs"))
    )

    private val UNKNOWN_KEYS = setOf(
        "", "unknown", "unknown genre", "other", "none", "genre", "misc", "miscellaneous", "various",
        "n a", "na", "undefined", "null", "no genre", "not specified", "unspecified", "blank",
        "untitled", "genre unknown", "other genre", "unclassified", "uncategorized", "general"
    )

    // The standard ID3v1 genre table (0..79) used by "(13)" / "13" style tags.
    private val ID3V1 = arrayOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock"
    )

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val PAREN_NUM = Regex("\\((\\d{1,3})\\)")
    private val ONLY_NUM = Regex("^\\d{1,3}$")
    private val SPLIT = Regex("[;/|\\\\,\\u0000]+|\\s[&+]\\s|\\sand\\s")

    /** Lower-case, '&' -> "and", everything that is not a letter/digit -> one space. */
    fun key(s: String): String =
        NON_ALNUM.replace(s.lowercase(Locale.ROOT).replace("&", " and "), " ").trim()

    fun isUnknown(raw: String?): Boolean {
        if (raw == null) return true
        return canonical(raw).isEmpty()
    }

    fun familyOf(genre: String): String = FAMILY[genre] ?: "Other"

    /**
     * Every distinct genre in [raw] as clean canonical names, best (first listed) first.
     * Unknown / "Other" / junk values give an empty list.
     */
    fun canonical(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()

        // numeric ID3v1 references: "(13)", "(17)(13)", "13", "(13)Pop"
        var text = raw.trim()
        if (ONLY_NUM.matches(text)) {
            ID3V1.getOrNull(text.toInt())?.let { out.addAll(canonical(it)) }
            return out.toList()
        }
        for (m in PAREN_NUM.findAll(text)) {
            ID3V1.getOrNull(m.groupValues[1].toInt())?.let { out.addAll(canonical(it)) }
        }
        text = PAREN_NUM.replace(text, " ").trim()
        if (text.isEmpty()) return out.toList()

        // "Rock & Roll" / "Jazz+Funk" are single genres; "pop;rock" / "R&B/Soul" list several.
        val listed = text.any { it == ';' || it == '/' || it == '|' || it == '\\' || it == ',' || it == '\u0000' }
        val whole = if (listed) null else ALIAS[key(text)]
        if (whole != null) {
            out.add(whole)
            return out.toList()
        }
        if (UNKNOWN_KEYS.contains(key(text))) return out.toList()

        for (part in SPLIT.split(text)) addPart(part, out)
        return out.toList()
    }

    private fun addPart(part: String, out: MutableSet<String>) {
        val p = part.trim()
        if (p.isEmpty()) return
        val k = key(p)
        if (k in UNKNOWN_KEYS) return
        val hit = ALIAS[k]
        if (hit != null) { out.add(hit); return }
        // Unknown but real-looking genre (e.g. "Bossa Nova", "Gamelan"): keep it, nicely spelled.
        if (looksLikeGenre(p)) out.add(titleCase(p))
    }

    private fun looksLikeGenre(p: String): Boolean {
        if (p.length < 2 || p.length > 32) return false
        if (p.all { it.isDigit() || it == ' ' || it == '-' }) return false
        val lower = p.lowercase(Locale.ROOT)
        if ("http" in lower || "www" in lower || ".com" in lower || ".net" in lower) return false
        return p.any { it.isLetter() }
    }

    private fun titleCase(p: String): String =
        p.trim().split(Regex("\\s+")).joinToString(" ") { w ->
            if (w.length <= 3 && w.all { it.isLetter() } && w == w.uppercase(Locale.ROOT)) w
            else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

    private val ALIAS: Map<String, String>
    private val FAMILY: Map<String, String>

    init {
        val alias = HashMap<String, String>()
        val family = HashMap<String, String>()
        for (d in DEFS) {
            family[d.name] = d.family
            alias[key(d.name)] = d.name
            for (a in d.aliases) alias.putIfAbsent(key(a), d.name)
        }
        ALIAS = alias
        FAMILY = family
    }
}
