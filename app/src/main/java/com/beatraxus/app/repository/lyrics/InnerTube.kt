package com.beatraxus.app.repository.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared plumbing for the YouTube (InnerTube) based lyric providers and the video-id resolver.
 *
 * Previously every caller built its JSON body by string concatenation (a title containing `"` or `\`
 * produced invalid JSON and a silent miss), sent a months-old hard-coded client version and no
 * `Origin`/`Referer`, and the shared OkHttp interceptor replaced any User-Agent with the app's own.
 */
internal object InnerTube {
    const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val MUSIC_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** WEB_REMIX versions are date based ("1.<yyyyMMdd>.01.00"); use yesterday so it is always valid. */
    fun webRemixVersion(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "1." + fmt.format(Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)) + ".01.00"
    }

    fun webRemixContext(): JsonObject = JsonObject().apply {
        add("client", JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", webRemixVersion())
            addProperty("hl", "en")
            addProperty("gl", "US")
        })
    }

    /** POST to a music.youtube.com InnerTube endpoint ("search", "next", "browse"). */
    suspend fun postMusic(endpoint: String, body: JsonObject, timeoutMs: Long = 6_000L): HttpResult {
        val url = "$MUSIC_ORIGIN/youtubei/v1/$endpoint".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("alt", "json")
            ?.addQueryParameter("prettyPrint", "false")
            ?.addQueryParameter("key", MUSIC_KEY)
            ?.build() ?: return HttpResult(0, null)
        val headers = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Origin" to MUSIC_ORIGIN,
            "Referer" to "$MUSIC_ORIGIN/",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Goog-Api-Format-Version" to "1",
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to webRemixVersion()
        )
        return LyricsHttp.post(
            url,
            body.toString().toRequestBody("application/json".toMediaType()),
            headers,
            timeoutMs
        )
    }
}

// ---- null-safe JSON navigation (JsonNull.asString / getAsJsonObject on JsonNull both throw) ----

/** Walk objects by key (String) and arrays by index (Int); null as soon as anything is missing or null. */
internal fun JsonElement?.ytAt(vararg path: Any): JsonElement? {
    var cur: JsonElement? = this
    for (key in path) {
        cur = when {
            cur == null || cur.isJsonNull -> return null
            key is String && cur.isJsonObject -> cur.asJsonObject.get(key)
            key is Int && cur.isJsonArray -> cur.asJsonArray.let { if (key in 0 until it.size()) it[key] else null }
            else -> return null
        }
    }
    return cur?.takeIf { !it.isJsonNull }
}

internal fun JsonElement?.ytStr(vararg path: Any): String? {
    val el = ytAt(*path) ?: return null
    return if (el.isJsonPrimitive) runCatching { el.asString }.getOrNull() else null
}

internal fun JsonElement?.ytLong(vararg path: Any): Long? {
    val el = ytAt(*path) ?: return null
    if (!el.isJsonPrimitive) return null
    return runCatching { el.asLong }.getOrNull()
}

internal fun JsonElement?.ytArr(vararg path: Any): JsonArray? = ytAt(*path)?.takeIf { it.isJsonArray }?.asJsonArray
