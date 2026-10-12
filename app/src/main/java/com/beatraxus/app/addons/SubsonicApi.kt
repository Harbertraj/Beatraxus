package com.beatraxus.app.addons

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure helpers for the Subsonic REST API (Navidrome, Airsonic, Gonic, Subsonic …).
 * No Android classes in here so the logic can be unit-tested on a plain JVM.
 */
internal object SubsonicApi {

    const val API_VERSION = "1.16.1"
    const val CLIENT_NAME = "beatraxus"

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Subsonic token auth: t = md5(password + salt). A fixed salt per sign-in keeps URLs stable (good for image caching). */
    fun makeCredentials(password: String, salt: String = randomSalt()): Pair<String, String> =
        salt to md5Hex(password + salt)

    private fun randomSalt(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** "navidrome.local:4533/" -> "http://navidrome.local:4533"; strips a trailing "/rest". */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
        u = u.trimEnd('/')
        if (u.endsWith("/rest", ignoreCase = true)) u = u.dropLast(5).trimEnd('/')
        return u
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun buildUrl(
        base: String,
        endpoint: String,
        username: String,
        salt: String,
        token: String,
        extra: Map<String, String> = emptyMap()
    ): String {
        val params = linkedMapOf(
            "u" to username,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT_NAME,
            "f" to "json"
        )
        params.putAll(extra)
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "$base/rest/$endpoint.view?$query"
    }

    /** Unwraps "subsonic-response"; throws a readable IOException if the server reports an error. */
    fun checkResponse(raw: String): JSONObject {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IOException("That doesn't look like a Subsonic server")
        }
        val resp = root.optJSONObject("subsonic-response")
            ?: throw IOException("That doesn't look like a Subsonic server")
        if (resp.optString("status") != "ok") {
            val err = resp.optJSONObject("error")
            val code = err?.optInt("code", 0) ?: 0
            val message = err?.optString("message").orEmpty()
            throw IOException(
                when (code) {
                    40, 41 -> "Wrong username or password"
                    50 -> "This account isn't allowed to do that"
                    else -> message.ifBlank { "Server returned an error" }
                }
            )
        }
        return resp
    }

    /** Subsonic returns a single object (not an array) when a list has exactly one entry on some servers. */
    private fun asArray(value: Any?): JSONArray = when (value) {
        is JSONArray -> value
        is JSONObject -> JSONArray().put(value)
        else -> JSONArray()
    }

    fun parseSongs(container: JSONObject?, coverUrl: (String) -> String): List<AddonMediaItem> {
        val songs = asArray(container?.opt("song"))
        return (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val artist = s.optString("artist")
            val album = s.optString("album")
            val cover = s.optString("coverArt").ifBlank { null }
            AddonMediaItem(
                id = id,
                title = s.optString("title").ifBlank { "Unknown" },
                subtitle = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                artworkUrl = cover?.let(coverUrl),
                mediaType = "audio",
                durationMs = s.optLong("duration", 0L) * 1000L,
                directPlayUrl = ""
            )
        }
    }
}
