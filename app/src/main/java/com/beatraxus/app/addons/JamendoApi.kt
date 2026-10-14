package com.beatraxus.app.addons

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Pure helpers for the official Jamendo API v3.0 (https://developer.jamendo.com/v3.0).
 * No Android classes in here so the logic can be unit-tested on a plain JVM.
 *
 * Only documented endpoints, parameters and fields are used:
 *  - `tracks`  : client_id, format, limit (max 200), offset, order, search, namesearch, artist_id, id,
 *                featured, imagesize, audioformat
 *  - `artists` : client_id, format, namesearch, limit, order
 * Track fields read: id, name, duration (seconds), artist_name, album_name, album_image, image, audio,
 * license_ccurl, shareurl. Response headers: status, code, error_message, results_count.
 */
internal object JamendoApi {

    const val BASE_URL = "https://api.jamendo.com/v3.0"
    const val PAGE_SIZE = 30

    /** One of the documented values of the `imagesize` parameter. */
    const val IMAGE_SIZE = 300

    /** Documented values of `audioformat`: mp31 (96 kbps, the API default), mp32, ogg, flac. */
    val AUDIO_FORMATS = listOf("mp31", "mp32", "ogg", "flac")
    const val DEFAULT_AUDIO_FORMAT = "mp32"

    const val MODE_ANY = "any"
    const val MODE_TITLE = "title"
    const val MODE_ARTIST = "artist"

    const val SORT_AUTO = "auto"
    const val SORT_FEATURED = "featured"

    /** `order` values (all documented) the sort chips may send; "auto" and "featured" are handled below. */
    private val ORDERS = setOf("popularity_week", "popularity_month", "popularity_total", "releasedate_desc")

    /** A Jamendo-level failure with a message that is safe to show to the user (never contains a URL or key). */
    class JamendoException(val code: Int, message: String) : IOException(message)

    data class TrackPage(val items: List<AddonMediaItem>, val rawCount: Int)

    data class Artist(
        val id: String,
        val name: String,
        val imageUrl: String?,
        val websiteUrl: String?,
        val pageUrl: String?
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun url(path: String, params: List<Pair<String, String>>): String =
        "$BASE_URL/$path/?" + params.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    /** Client IDs are pasted by hand: drop any whitespace that came along with them. */
    fun cleanClientId(raw: String): String = raw.filterNot { it.isWhitespace() }

    fun safeAudioFormat(format: String?): String =
        if (format != null && format in AUDIO_FORMATS) format else DEFAULT_AUDIO_FORMAT

    /**
     * Search / browse request.
     *  - [query] blank          : browse (popularity or the chosen sort)
     *  - [mode] title           : `namesearch` (track-name match)
     *  - [mode] any             : `search` (free text over track, album, artist and tags)
     *  - [artistIds] not empty  : tracks of those artists (`artist_id`, list separated by spaces/+ as documented)
     */
    fun tracksUrl(
        clientId: String,
        query: String?,
        mode: String,
        sort: String,
        offset: Int,
        limit: Int = PAGE_SIZE,
        audioFormat: String = DEFAULT_AUDIO_FORMAT,
        artistIds: List<String> = emptyList()
    ): String {
        val q = query?.trim().orEmpty()
        val p = mutableListOf(
            "client_id" to cleanClientId(clientId),
            "format" to "json",
            "limit" to limit.coerceIn(1, 200).toString(),
            "offset" to offset.coerceAtLeast(0).toString(),
            "imagesize" to IMAGE_SIZE.toString(),
            "audioformat" to safeAudioFormat(audioFormat)
        )
        val searching = q.isNotEmpty() && artistIds.isEmpty()
        when {
            artistIds.isNotEmpty() -> p += "artist_id" to artistIds.joinToString(" ")
            searching && mode == MODE_TITLE -> p += "namesearch" to q
            searching -> p += "search" to q
        }

        when {
            sort == SORT_FEATURED -> {
                p += "featured" to "1"
                p += "order" to "popularity_month"
            }
            sort in ORDERS -> p += "order" to sort
            // "auto": keep the API's default relevance order while searching, otherwise show what is popular.
            artistIds.isNotEmpty() -> p += "order" to "popularity_total"
            !searching -> p += "order" to "popularity_month"
        }
        return url("tracks", p)
    }

    /** One track by id: used to get a fresh playback URL right before playing and to detect removed tracks. */
    fun trackByIdUrl(clientId: String, trackId: String, audioFormat: String = DEFAULT_AUDIO_FORMAT): String = url(
        "tracks",
        listOf(
            "client_id" to cleanClientId(clientId),
            "format" to "json",
            "id" to trackId.trim(),
            "imagesize" to IMAGE_SIZE.toString(),
            "audioformat" to safeAudioFormat(audioFormat)
        )
    )

    /** Artists whose name contains [query] (most popular first), used for "search by artist". */
    fun artistsUrl(clientId: String, query: String, limit: Int = 5): String = url(
        "artists",
        listOf(
            "client_id" to cleanClientId(clientId),
            "format" to "json",
            "namesearch" to query.trim(),
            "order" to "popularity_total",
            "limit" to limit.coerceIn(1, 200).toString()
        )
    )

    /** Lightweight request used to check a client ID when the user enters one. */
    fun probeUrl(clientId: String): String = url(
        "tracks",
        listOf("client_id" to cleanClientId(clientId), "format" to "json", "limit" to "1")
    )

    fun messageForCode(code: Int, apiMessage: String?): String = when (code) {
        5 -> "Jamendo didn't accept this client ID — check it and try again"
        6 -> "Jamendo's request limit was reached — wait a moment and try again"
        11 -> "Jamendo has suspended this client ID"
        else -> apiMessage?.trim()?.takeIf { it.isNotBlank() && !it.contains("http", ignoreCase = true) }
            ?.let { "Jamendo error: $it" }
            ?: "Jamendo returned an error (code $code)"
    }

    fun httpMessage(httpCode: Int): String = when (httpCode) {
        429 -> "Jamendo's request limit was reached — wait a moment and try again"
        in 500..599 -> "Jamendo is having problems right now (HTTP $httpCode) — try again later"
        else -> "Jamendo answered with an error (HTTP $httpCode)"
    }

    /** Validates the `headers` block and returns `results`. Throws [JamendoException] with a readable message. */
    fun parseResults(raw: String): JSONArray {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw JamendoException(-1, "Jamendo sent a response Beatraxus couldn't read")
        }
        val headers = root.optJSONObject("headers")
            ?: throw JamendoException(-1, "That doesn't look like a Jamendo response")
        val code = when {
            headers.has("code") -> headers.optInt("code", -1)
            headers.optString("status") == "success" -> 0
            else -> -1
        }
        if (code != 0) throw JamendoException(code, messageForCode(code, headers.optString("error_message")))
        return root.optJSONArray("results") ?: JSONArray()
    }

    fun parseTracks(raw: String): TrackPage {
        val results = parseResults(raw)
        val items = ArrayList<AddonMediaItem>(results.length())
        for (i in 0 until results.length()) {
            val track = results.optJSONObject(i) ?: continue
            toItem(track)?.let { items.add(it) }
        }
        return TrackPage(items, results.length())
    }

    /** A track that has no id or no streaming URL can't be played, so it is skipped rather than shown dead. */
    fun toItem(t: JSONObject): AddonMediaItem? {
        val id = str(t, "id")
        val audio = secure(str(t, "audio"))
        if (id.isBlank() || audio.isBlank()) return null
        val artist = str(t, "artist_name")
        val album = str(t, "album_name") // empty for singles
        val art = secure(str(t, "image").ifBlank { str(t, "album_image") })
        val seconds = str(t, "duration").toDoubleOrNull()?.toLong() ?: 0L
        return AddonMediaItem(
            id = id,
            title = str(t, "name").ifBlank { "Untitled" },
            subtitle = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            artworkUrl = art.ifBlank { null },
            mediaType = "audio",
            durationMs = seconds.coerceAtLeast(0L) * 1000L,
            directPlayUrl = audio,
            artist = artist.ifBlank { null },
            album = album.ifBlank { null },
            pageUrl = secure(str(t, "shareurl")).ifBlank { null },
            licenseUrl = secure(str(t, "license_ccurl")).ifBlank { null }
        )
    }

    fun parseArtists(raw: String): List<Artist> {
        val results = parseResults(raw)
        val out = ArrayList<Artist>(results.length())
        for (i in 0 until results.length()) {
            val a = results.optJSONObject(i) ?: continue
            val id = str(a, "id")
            val name = str(a, "name")
            if (id.isBlank() || name.isBlank()) continue
            out.add(
                Artist(
                    id = id,
                    name = name,
                    imageUrl = secure(str(a, "image")).ifBlank { null },
                    websiteUrl = secure(str(a, "website")).ifBlank { null },
                    pageUrl = secure(str(a, "shareurl")).ifBlank { null }
                )
            )
        }
        return out
    }

    /** More results are likely when the page came back full (the API's `results_count` equals what we asked for). */
    fun hasMore(rawCount: Int, limit: Int = PAGE_SIZE): Boolean = rawCount >= limit

    /** Missing and JSON-null both become "" (org.json would otherwise turn null into the text "null"). */
    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    /** Jamendo's documented samples hand out http:// licence links; upgrade them to https. */
    fun secure(url: String): String = httpsOnly(url)
}
