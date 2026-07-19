package com.beatraxus.app.repository

import com.beatraxus.app.model.RadioStation
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object RadioBrowserApi {
    // Public mirrors of the free Radio-Browser API — no key required.
    private val hosts = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info"
    )

    fun stationsByCountry(country: String, limit: Int = 100): List<RadioStation> {
        for (host in hosts) {
            try {
                val url = "$host/json/stations/bycountry/${java.net.URLEncoder.encode(country, "UTF-8")}?limit=$limit&hidebroken=true&order=clickcount&reverse=true"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Beatraxus/1.0")
                }
                val body = conn.inputStream.bufferedReader().readText()
                return parse(body)
            } catch (e: Exception) { /* try next mirror */ }
        }
        return emptyList()
    }

    /**
     * Stations tagged with a genre/language keyword (e.g. "tamil"), ordered by
     * popularity. Radio-Browser's `tags` field is community-maintained, so this
     * catches stations that self-tag as Tamil regardless of which country they're
     * registered under (includes India, Sri Lanka, Malaysia, Singapore, diaspora
     * stations, etc).
     */
    fun stationsByTag(tag: String, limit: Int = 100): List<RadioStation> {
        for (host in hosts) {
            try {
                val url = "$host/json/stations/bytag/${java.net.URLEncoder.encode(tag, "UTF-8")}?limit=$limit&hidebroken=true&order=clickcount&reverse=true"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", "Beatraxus/1.0")
                }
                return parse(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) { /* try next mirror */ }
        }
        return emptyList()
    }

    /** Stations whose primary broadcast language is set to the given language. */
    fun stationsByLanguage(language: String, limit: Int = 100): List<RadioStation> {
        for (host in hosts) {
            try {
                val url = "$host/json/stations/bylanguage/${java.net.URLEncoder.encode(language, "UTF-8")}?limit=$limit&hidebroken=true&order=clickcount&reverse=true"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", "Beatraxus/1.0")
                }
                return parse(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) { /* try next mirror */ }
        }
        return emptyList()
    }

    /**
     * Union of the tag- and language-based Tamil lookups, deduplicated by
     * station UUID and sorted so results feel like one curated, popularity-ordered
     * list. This is what backs the default "Tamil" radio section — no typing
     * required.
     */
    fun tamilStations(limit: Int = 100): List<RadioStation> {
        val byTag = runCatching { stationsByTag("tamil", limit) }.getOrDefault(emptyList())
        val byLanguage = runCatching { stationsByLanguage("tamil", limit) }.getOrDefault(emptyList())
        return (byTag + byLanguage)
            .distinctBy { it.id.ifBlank { it.streamUrl } }
            .take(limit)
    }

    fun topStations(limit: Int = 200): List<RadioStation> {
        for (host in hosts) {
            try {
                val url = "$host/json/stations/topclick/$limit?hidebroken=true"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", "Beatraxus/1.0")
                }
                return parse(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) { }
        }
        return emptyList()
    }

    private fun parse(body: String): List<RadioStation> {
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (url.isBlank()) return@mapNotNull null
            RadioStation(
                id = o.optString("stationuuid"),
                name = o.optString("name"),
                streamUrl = url,
                country = o.optString("country"),
                band = if (o.optInt("bitrate") in 1..64) "AM" else "FM",
                favicon = o.optString("favicon").ifBlank { null }
            )
        }
    }
}
