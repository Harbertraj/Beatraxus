package com.beatraxus.app.addons

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Pure helpers for the Internet Archive's public APIs (advancedsearch.php and /metadata/<id>).
 * No Android classes in here so the logic can be unit-tested on a plain JVM.
 */
internal object ArchiveOrgApi {

    const val PAGE_SIZE = 50

    const val TYPE_ALL = "all"
    const val TYPE_VIDEO = "video"
    const val TYPE_AUDIO = "audio"

    const val SORT_POPULAR = "downloads"
    const val SORT_TRENDING = "week"
    const val SORT_NEWEST = "publicdate"

    const val QUALITY_STANDARD = "standard"
    const val QUALITY_LOSSLESS = "lossless"

    private val SORTS = setOf(SORT_POPULAR, SORT_TRENDING, SORT_NEWEST)

    val VIDEO_EXT = listOf("mp4", "m4v", "webm", "mkv", "mov", "mpeg4", "mpg", "avi", "ogv")
    val AUDIO_EXT = listOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")

    /** Lossless first, then the usual order, so an item without lossless files still plays. */
    private val AUDIO_EXT_LOSSLESS = listOf("flac", "wav") + AUDIO_EXT.filter { it != "flac" && it != "wav" }

    private val BROWSE_COLLECTIONS =
        "feature_films OR prelinger OR classic_tv OR opensource_movies OR opensource_audio OR etree OR " +
            "netlabels OR audio_music OR oldtimeradio"

    // ---- search ---------------------------------------------------------------------------------------

    fun cleanQuery(text: String): String =
        text.replace(Regex("""[+\-&|!(){}\[\]^"~*?:\\/]"""), " ").replace(Regex("""\s+"""), " ").trim()

    fun searchQuery(text: String, type: String): String {
        val types = when (type) {
            TYPE_VIDEO -> "movies"
            TYPE_AUDIO -> "audio OR etree" // etree = the Live Music Archive's concert recordings
            else -> "movies OR audio OR etree"
        }
        val clean = cleanQuery(text)
        // Skip borrow-only / restricted items (TV news recordings etc.): they have no downloadable file.
        val base = "mediatype:($types) AND -access-restricted-item:true AND -collection:(tvnews OR tvarchive)"
        return if (clean.isBlank()) "$base AND collection:($BROWSE_COLLECTIONS)" else "($clean) AND $base"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** [page] is 0-based; advancedsearch.php counts pages from 1. */
    fun searchUrl(text: String, type: String, sort: String, page: Int): String {
        val order = if (sort in SORTS) sort else SORT_POPULAR
        val params = listOf(
            "q" to searchQuery(text, type),
            "fl[]" to "identifier",
            "fl[]" to "title",
            "fl[]" to "mediatype",
            "fl[]" to "year",
            "fl[]" to "creator",
            "fl[]" to "runtime",
            "fl[]" to "licenseurl",
            "sort[]" to "$order desc",
            "rows" to PAGE_SIZE.toString(),
            "page" to (page.coerceAtLeast(0) + 1).toString(),
            "output" to "json"
        )
        return "https://archive.org/advancedsearch.php?" + params.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
    }

    data class SearchPage(val items: List<AddonMediaItem>, val numFound: Long)

    fun parseSearch(raw: String): SearchPage {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IOException("Internet Archive sent a response Beatraxus couldn't read")
        }
        val response = root.optJSONObject("response") ?: return SearchPage(emptyList(), 0L)
        val docs = response.optJSONArray("docs") ?: return SearchPage(emptyList(), 0L)
        val items = ArrayList<AddonMediaItem>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = text(doc, "identifier")
            if (identifier.isBlank()) continue
            val creator = text(doc, "creator")
            val year = text(doc, "year")
            val isVideo = text(doc, "mediatype") == "movies"
            items.add(
                AddonMediaItem(
                    id = identifier,
                    title = text(doc, "title").ifBlank { identifier },
                    subtitle = listOf(creator, year).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    artworkUrl = artworkUrl(identifier),
                    mediaType = if (isVideo) "video" else "audio",
                    durationMs = parseDurationMs(text(doc, "runtime")),
                    directPlayUrl = "",
                    artist = creator.ifBlank { null },
                    pageUrl = detailsUrl(identifier),
                    licenseUrl = text(doc, "licenseurl").takeIf { it.startsWith("http") }?.let(::httpsOnly)
                )
            )
        }
        return SearchPage(items, response.optLong("numFound", items.size.toLong()))
    }

    fun hasMore(page: Int, numFound: Long): Boolean = (page.toLong() + 1L) * PAGE_SIZE < numFound

    /** Archive fields are a string for some items and an array for others; take the first non-blank value. */
    fun text(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        return when (val v = o.opt(key)) {
            is JSONArray -> (0 until v.length()).map { if (v.isNull(it)) "" else v.optString(it).trim() }
                .firstOrNull { it.isNotBlank() }.orEmpty()
            else -> o.optString(key).trim()
        }
    }

    // ---- URLs -----------------------------------------------------------------------------------------

    fun artworkUrl(identifier: String) = "https://archive.org/services/img/${pathEncode(identifier)}"

    fun detailsUrl(identifier: String) = "https://archive.org/details/${pathEncode(identifier)}"

    fun metadataUrl(identifier: String) = "https://archive.org/metadata/${pathEncode(identifier)}"

    fun downloadUrl(identifier: String, fileName: String) =
        "https://archive.org/download/${pathEncode(identifier)}/${pathEncode(fileName, keepSlash = true)}"

    /** Percent-encodes like android.net.Uri.encode (UTF-8, keeps `_-!.~'()*`); optionally keeps `/`. */
    fun pathEncode(s: String, keepSlash: Boolean = false): String {
        val keep = "_-!.~'()*"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in keep || (keepSlash && c == '/')) {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    // ---- durations ------------------------------------------------------------------------------------

    /** "215.3", "3:35", "01:25:37" or "1:25:37.5" → milliseconds; anything else → 0. */
    fun parseDurationMs(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        val seconds: Double = if (':' in s) {
            val parts = s.split(':')
            if (parts.size > 3) return 0L
            var total = 0.0
            for (p in parts) {
                val v = p.trim().toDoubleOrNull() ?: return 0L
                if (v < 0) return 0L
                total = total * 60 + v
            }
            total
        } else {
            s.toDoubleOrNull() ?: return 0L
        }
        return if (seconds.isNaN() || seconds < 0) 0L else (seconds * 1000).toLong()
    }

    // ---- file selection -------------------------------------------------------------------------------

    private val excludedName = Regex("""(^|[/_\- .])(sample|thumb)([._\- ]|$)|__ia_thumb""", RegexOption.IGNORE_CASE)

    private fun ext(name: String) = name.lowercase().substringAfterLast('.', "")

    private fun usable(f: JSONObject): Boolean {
        if (f.optString("private") == "true") return false
        val name = f.optString("name")
        return name.isNotBlank() && !excludedName.containsMatchIn(name)
    }

    private fun sizeOf(f: JSONObject) = f.optString("size").toLongOrNull() ?: 0L

    private fun isDerivative(f: JSONObject) = f.optString("source") == "derivative"

    /** Archive's own video derivatives, best first: HD h.264 > h.264 > generic MPEG4 > the tiny "512Kb" proxy. */
    fun videoTier(format: String): Int {
        val f = format.trim().lowercase()
        return when {
            f.contains("512kb") -> 1
            f.contains("264") && f.contains("hd") -> 5
            f == "h.264 ia" -> 3
            f.contains("264") -> 4
            f == "mpeg4" -> 3
            else -> 2
        }
    }

    /** Audio derivatives by bitrate: "VBR MP3" (~190 kbps) beats "128Kbps MP3" beats "64Kbps MP3". */
    fun audioTier(format: String): Int {
        val f = format.lowercase()
        val kbps = Regex("""(\d{2,3})\s*kbps""").find(f)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            kbps != null -> (kbps / 10).coerceIn(0, 60)
            f.contains("vbr") -> 20
            else -> 10
        }
    }

    private fun score(rank: Int, listSize: Int, tier: Int, derivative: Boolean, bytes: Long): Long =
        (listSize - rank).toLong() * 10_000_000_000_000_000L +
            tier.toLong() * 100_000_000_000_000L +
            (if (derivative) 1L else 0L) * 100_000_000_000L +
            minOf(bytes, 99_999_999_999L)

    /** Best file of [exts] (earlier = better) among [files], or null. [tier] ranks formats inside one extension. */
    private fun bestOf(files: List<JSONObject>, exts: List<String>, tier: (JSONObject) -> Int): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Long.MIN_VALUE
        for (f in files) {
            val rank = exts.indexOf(ext(f.optString("name")))
            if (rank < 0) continue
            val s = score(rank, exts.size, tier(f), isDerivative(f), sizeOf(f))
            if (s > bestScore) { bestScore = s; best = f }
        }
        return best
    }

    private fun filesOf(meta: JSONObject): List<JSONObject> {
        val arr = meta.optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter(::usable)
    }

    /**
     * Throws a readable error when an item can't be streamed at all: unknown/removed, dark, or borrow-only.
     * (An empty metadata object is what the Archive returns for an identifier that doesn't exist.)
     */
    fun requirePlayable(meta: JSONObject) {
        if (meta.length() == 0) throw IOException("This item isn't available any more (it may have been removed)")
        val restricted = meta.optBoolean("is_dark", false) ||
            meta.optJSONObject("metadata")?.optString("access-restricted-item") == "true"
        if (restricted) throw IOException("This item is restricted or borrow-only, so it can't be streamed")
        if ((meta.optJSONArray("files")?.length() ?: 0) == 0) {
            throw IOException("This item has no downloadable files (it may be restricted or borrow-only)")
        }
    }

    /**
     * The one file to stream for a video or single-file item. Prefers the requested kind but falls back to the
     * other: Archive "movies" items are sometimes audio-only and vice versa. Returns the file name or null.
     */
    fun pickStreamFile(meta: JSONObject, wantVideo: Boolean, quality: String): String? {
        val files = filesOf(meta)
        val audioExts = if (quality == QUALITY_LOSSLESS) AUDIO_EXT_LOSSLESS else AUDIO_EXT
        val videoPick = { bestOf(files, VIDEO_EXT) { videoTier(it.optString("format")) } }
        val audioPick = {
            bestOf(files, audioExts) { if (quality == QUALITY_LOSSLESS) 0 else audioTier(it.optString("format")) }
        }
        val order = if (wantVideo) listOf(videoPick, audioPick) else listOf(audioPick, videoPick)
        for (pick in order) pick()?.let { return it.optString("name") }
        return null
    }

    // ---- albums ---------------------------------------------------------------------------------------

    private val derivativeSuffix = Regex("""[_\-](vbr|\d{2,3}\s*kb(ps)?)$""", RegexOption.IGNORE_CASE)

    private fun withoutExt(name: String) =
        if ('.' in name.substringAfterLast('/')) name.substringBeforeLast('.') else name

    /** "Disc1/track01_64kb.mp3" and "Disc1/track01.flac" are the same track in two formats. */
    fun trackKey(name: String): String = withoutExt(name).lowercase().replace(derivativeSuffix, "")

    private fun parseTrackNumber(raw: String): Int? = Regex("""^\s*(\d{1,3})""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

    /** Title fallback for a file without a title tag: no folder, no extension, no "_vbr"/"_64kb" format suffix. */
    private fun prettyName(name: String): String =
        withoutExt(name.substringAfterLast('/')).replace(derivativeSuffix, "").replace('_', ' ').trim()

    /** Compares "track2" before "track10" (digit runs are compared as numbers). */
    fun naturalCompare(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var ei = i; while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j; while (ej < y.length && y[ej].isDigit()) ej++
                val nx = x.substring(i, ei).trimStart('0')
                val ny = y.substring(j, ej).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++; j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

    /**
     * Turns an audio item into its playable tracks: one entry per track (formats of the same track are merged),
     * best format per [quality], in track order, with real titles and durations from the file metadata.
     * Returns an empty list when the item has no audio files.
     */
    fun audioTracks(meta: JSONObject, itemId: String, itemTitle: String, quality: String): List<AddonMediaItem> {
        val md = meta.optJSONObject("metadata") ?: JSONObject()
        val itemCreator = text(md, "creator")
        val license = text(md, "licenseurl").takeIf { it.startsWith("http") }?.let(::httpsOnly)
        val exts = if (quality == QUALITY_LOSSLESS) AUDIO_EXT_LOSSLESS else AUDIO_EXT

        val groups = filesOf(meta).filter { ext(it.optString("name")) in exts }
            .groupBy { trackKey(it.optString("name")) }
        val chosen = groups.values.mapNotNull { group ->
            bestOf(group, exts) { if (quality == QUALITY_LOSSLESS) 0 else audioTier(it.optString("format")) }
        }
        if (chosen.isEmpty()) return emptyList()

        val numbers = chosen.map { parseTrackNumber(it.optString("track")) }
        val byNumber = numbers.all { it != null } && numbers.toSet().size == numbers.size
        val ordered = if (byNumber) {
            chosen.sortedWith(compareBy({ parseTrackNumber(it.optString("track")) }, { it.optString("name") }))
        } else {
            chosen.sortedWith { a, b -> naturalCompare(a.optString("name"), b.optString("name")) }
        }

        val art = artworkUrl(itemId)
        val page = detailsUrl(itemId)
        return ordered.mapIndexed { index, f ->
            val name = f.optString("name")
            val fileTitle = text(f, "title")
            val title = when {
                fileTitle.isNotBlank() -> fileTitle
                ordered.size == 1 -> itemTitle.ifBlank { prettyName(name) }
                else -> prettyName(name)
            }
            val artist = text(f, "creator").ifBlank { text(f, "artist") }.ifBlank { itemCreator }
            AddonMediaItem(
                // The app stores queue ids comma-separated, and Archive file names may contain commas.
                id = "$itemId/${name.replace(",", "%2C")}",
                title = title,
                subtitle = listOf(artist, itemTitle).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                artworkUrl = art,
                mediaType = "audio",
                durationMs = parseDurationMs(f.optString("length")),
                directPlayUrl = downloadUrl(itemId, name),
                artist = artist.ifBlank { null },
                album = itemTitle.ifBlank { null },
                trackNumber = parseTrackNumber(f.optString("track")) ?: (index + 1),
                pageUrl = page,
                licenseUrl = license
            )
        }
    }
}
