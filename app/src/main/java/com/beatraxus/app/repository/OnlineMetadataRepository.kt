package com.beatraxus.app.repository

import android.util.Log
import com.beatraxus.app.util.ArtistNameUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Online artist / album details for the library detail screens.
 *
 * All sources are public and need no API key:
 *  - Deezer search API   -> artist photo, fan count, album count, album cover (fallback)
 *  - iTunes Search API   -> album cover (high-res), release year, genre, track count
 *  - Wikipedia REST API  -> artist biography / album description (CC BY-SA, link is surfaced in the UI)
 *
 * Results are cached in memory for the process lifetime (images are cached on disk by Coil).
 * Every lookup fails soft: offline or no match simply returns null and the UI keeps the local data.
 */
object OnlineMetadataRepository {

    data class ArtistInfo(
        val name: String,
        val imageUrl: String?,
        val fans: Long?,
        val albumCount: Int?,
        val description: String?,
        val bio: String?,
        val wikiUrl: String?
    )

    data class AlbumInfo(
        val title: String,
        val imageUrl: String?,
        val year: Int?,
        val genre: String?,
        val trackCount: Int?,
        val copyright: String?,
        val about: String?,
        val wikiUrl: String?
    )

    private const val TAG = "OnlineMetadata"
    private const val UA = "Beatraxus/1.0 ( https://github.com/Harbertraj/Beatraxus )"
    private const val MISS_TTL_MS = 5 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val artistCache = ConcurrentHashMap<String, ArtistInfo>()
    private val albumCache = ConcurrentHashMap<String, AlbumInfo>()
    private val misses = ConcurrentHashMap<String, Long>()

    private val ARTIST_RX = Regex(
        "singer|composer|musician|band|rapper|producer|vocalist|songwriter|duo|group|disc jockey|\\bDJ\\b|" +
            "music director|instrumentalist|guitarist|pianist|violinist|lyricist|playback",
        RegexOption.IGNORE_CASE
    )
    private val ALBUM_RX = Regex(
        "album|soundtrack|score|extended play|\\bEP\\b|mixtape|film|single",
        RegexOption.IGNORE_CASE
    )

    private fun recentMiss(key: String): Boolean {
        val t = misses[key] ?: return false
        if (System.currentTimeMillis() - t > MISS_TTL_MS) { misses.remove(key); return false }
        return true
    }

    // ------------------------------------------------------------------ artist

    suspend fun artistInfo(name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        val key = ArtistNameUtils.normalizeKey(name)
        if (key.isEmpty()) return@withContext null
        artistCache[key]?.let { return@withContext it }
        if (recentMiss("a:$key")) return@withContext null

        val info = try {
            coroutineScope {
                val deezer = async { runCatching { deezerArtist(name) }.getOrNull() }
                val wiki = async { runCatching { wikiSummary("$name musician singer", ARTIST_RX, name, null) }.getOrNull() }
                val d = deezer.await()
                val w = wiki.await()
                if (d == null && w == null) null
                else ArtistInfo(
                    name = name,
                    imageUrl = d?.imageUrl ?: w?.thumb,
                    fans = d?.fans,
                    albumCount = d?.albums,
                    description = w?.description?.takeIf { it.isNotBlank() },
                    bio = w?.extract,
                    wikiUrl = w?.url
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "artistInfo failed for $name: ${e.message}")
            null
        }
        if (info != null) artistCache[key] = info else misses["a:$key"] = System.currentTimeMillis()
        info
    }

    private class DeezerArtist(val imageUrl: String?, val fans: Long?, val albums: Int?)

    private fun deezerArtist(name: String): DeezerArtist? {
        val json = getJson("https://api.deezer.com/search/artist?limit=5&q=${enc(name)}") ?: return null
        val arr = json.optJSONArray("data") ?: return null
        val key = ArtistNameUtils.normalizeKey(name)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (ArtistNameUtils.normalizeKey(o.optString("name")) != key) continue
            // Deezer serves a generic placeholder ("/artist//") when it has no photo
            val pic = o.optString("picture_xl").takeIf { it.isNotBlank() && !it.contains("/artist//") }
            return DeezerArtist(
                imageUrl = pic,
                fans = o.optLong("nb_fan", -1L).takeIf { it >= 0 },
                albums = o.optInt("nb_album", -1).takeIf { it >= 0 }
            )
        }
        return null
    }

    // ------------------------------------------------------------------ album

    suspend fun albumInfo(album: String, artist: String?): AlbumInfo? = withContext(Dispatchers.IO) {
        val key = ArtistNameUtils.normalizeKey(album) + "|" + ArtistNameUtils.normalizeKey(artist.orEmpty())
        if (key.startsWith("|")) return@withContext null
        albumCache[key]?.let { return@withContext it }
        if (recentMiss("b:$key")) return@withContext null

        val cleaned = cleanAlbum(album)
        val info = try {
            coroutineScope {
                val itunes = async { runCatching { itunesAlbum(cleaned, artist) }.getOrNull() }
                val wiki = async {
                    runCatching {
                        wikiSummary("$cleaned ${artist.orEmpty()} soundtrack album", ALBUM_RX, cleaned, artist)
                    }.getOrNull()
                }
                val i = itunes.await()
                val w = wiki.await()
                val cover = i?.imageUrl ?: runCatching { deezerAlbumCover(cleaned, artist) }.getOrNull() ?: w?.thumb
                if (i == null && w == null && cover == null) null
                else AlbumInfo(
                    title = album,
                    imageUrl = cover,
                    year = i?.year,
                    genre = i?.genre,
                    trackCount = i?.tracks,
                    copyright = i?.copyright,
                    about = w?.extract,
                    wikiUrl = w?.url
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "albumInfo failed for $album: ${e.message}")
            null
        }
        if (info != null) albumCache[key] = info else misses["b:$key"] = System.currentTimeMillis()
        info
    }

    private class ItunesAlbum(val imageUrl: String?, val year: Int?, val genre: String?, val tracks: Int?, val copyright: String?)

    private fun itunesAlbum(album: String, artist: String?): ItunesAlbum? {
        val term = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album).joinToString(" ")
        val json = getJson("https://itunes.apple.com/search?media=music&entity=album&limit=10&term=${enc(term)}") ?: return null
        val arr = json.optJSONArray("results") ?: return null
        val target = ArtistNameUtils.normalizeKey(album)
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = ArtistNameUtils.normalizeKey(cleanAlbum(o.optString("collectionName")))
            if (!artistMatches(artist, o.optString("artistName"))) continue
            if (name == target) { best = o; break }
            if (best == null && target.length >= 4 && (name.contains(target) || target.contains(name)) && name.isNotEmpty()) best = o
        }
        val o = best ?: return null
        val art = o.optString("artworkUrl100").takeIf { it.isNotBlank() }
            ?.replace("100x100bb", "1200x1200bb")
        return ItunesAlbum(
            imageUrl = art,
            year = o.optString("releaseDate").take(4).toIntOrNull()?.takeIf { it in 1900..2100 },
            genre = o.optString("primaryGenreName").takeIf { it.isNotBlank() },
            tracks = o.optInt("trackCount", -1).takeIf { it > 0 },
            copyright = o.optString("copyright").takeIf { it.isNotBlank() }
        )
    }

    private fun deezerAlbumCover(album: String, artist: String?): String? {
        val term = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album).joinToString(" ")
        val json = getJson("https://api.deezer.com/search/album?limit=6&q=${enc(term)}") ?: return null
        val arr = json.optJSONArray("data") ?: return null
        val target = ArtistNameUtils.normalizeKey(album)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = ArtistNameUtils.normalizeKey(cleanAlbum(o.optString("title")))
            val a = o.optJSONObject("artist")?.optString("name").orEmpty()
            if (title == target && artistMatches(artist, a)) {
                return o.optString("cover_xl").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ wikipedia

    private class Wiki(val description: String?, val extract: String, val url: String?, val thumb: String?)

    private fun wikiSummary(query: String, mustMatch: Regex, nameHint: String, artistHint: String?): Wiki? {
        val search = getJson(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=4&srsearch=${enc(query)}"
        ) ?: return null
        val hits = search.optJSONObject("query")?.optJSONArray("search") ?: return null
        val hint = ArtistNameUtils.normalizeKey(nameHint)
        for (i in 0 until hits.length()) {
            val title = hits.getJSONObject(i).optString("title")
            val nt = ArtistNameUtils.normalizeKey(title)
            if (hint.isNotEmpty() && nt.isNotEmpty() && !(nt.contains(hint) || hint.contains(nt))) continue
            val path = enc(title.replace(' ', '_'))
            val s = getJson("https://en.wikipedia.org/api/rest_v1/page/summary/$path") ?: continue
            if (s.optString("type") == "disambiguation") continue
            val extract = s.optString("extract")
            if (extract.isBlank()) continue
            val desc = s.optString("description")
            if (!mustMatch.containsMatchIn("$desc ${extract.take(300)}")) continue
            if (!artistHint.isNullOrBlank()) {
                val words = significantWords(artistHint)
                if (words.isNotEmpty() && words.none { extract.contains(it, ignoreCase = true) }) continue
            }
            val thumb = s.optJSONObject("thumbnail")?.optString("source")
                ?.takeIf { it.isNotBlank() }
                ?.replace(Regex("/\\d+px-"), "/800px-")
            val url = s.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
                ?.takeIf { it.isNotBlank() }
            return Wiki(desc.takeIf { it.isNotBlank() }, extract, url, thumb)
        }
        return null
    }

    // ------------------------------------------------------------------ helpers

    private fun significantWords(s: String) =
        s.split(Regex("[^A-Za-z0-9]+")).filter { it.length >= 4 }

    /** True when no artist was given, the result is a compilation, or the names share a significant word. */
    private fun artistMatches(wanted: String?, found: String): Boolean {
        if (wanted.isNullOrBlank()) return true
        if (found.equals("Various Artists", ignoreCase = true)) return true
        val words = significantWords(wanted)
        if (words.isEmpty()) return ArtistNameUtils.normalizeKey(found).contains(ArtistNameUtils.normalizeKey(wanted))
        return words.any { found.contains(it, ignoreCase = true) }
    }

    /** "3 (Original Motion Picture Soundtrack)" -> "3" */
    private fun cleanAlbum(s: String): String =
        s.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").trim().ifEmpty { s.trim() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JSONObject? {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string() ?: return null
            return JSONObject(body)
        }
    }
}
