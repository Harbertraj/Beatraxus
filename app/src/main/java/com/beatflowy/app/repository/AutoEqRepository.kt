package com.beatflowy.app.repository

import android.content.Context
import com.beatflowy.app.model.AutoEqProfile
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class AutoEqRepository(private val context: Context) {
    suspend fun searchProfiles(query: String, limit: Int = 12): List<AutoEqProfileSummary> = withContext(Dispatchers.IO) {
        val normalized = query.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return@withContext emptyList()

        val indexContent = loadIndex()
        val regex = Regex("""- \[(.+?)]\(\./(.+?)\) by (.+)""")
        indexContent.lineSequence()
            .mapNotNull { line ->
                val match = regex.find(line.trim()) ?: return@mapNotNull null
                val name = match.groupValues[1]
                val path = match.groupValues[2]
                val source = match.groupValues[3]
                if (!name.lowercase(Locale.US).contains(normalized)) return@mapNotNull null
                AutoEqProfileSummary(name = name, relativePath = path, source = source)
            }
            .distinctBy { "${it.name}|${it.relativePath}" }
            .take(limit)
            .toList()
    }

    suspend fun loadProfile(summary: AutoEqProfileSummary): AutoEqProfile = withContext(Dispatchers.IO) {
        val apiPath = summary.relativePath
            .split("/")
            .joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        val contentsUrl = URL("https://api.github.com/repos/jaakkopasanen/AutoEq/contents/results/$apiPath")
        val listing = readUrl(contentsUrl)
        val files = JSONArray(listing)
        var downloadUrl: String? = null
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index)
            val name = item.optString("name")
            if (name.endsWith("ParametricEQ.txt")) {
                downloadUrl = item.optString("download_url")
                break
            }
        }
        val parametricUrl = downloadUrl ?: error("No parametric profile available")
        parseProfile(summary, readUrl(URL(parametricUrl)))
    }

    private fun parseProfile(summary: AutoEqProfileSummary, body: String): AutoEqProfile {
        val preampRegex = Regex("""Preamp:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val filterRegex = Regex(
            """Filter\s+(\d+):\s+(ON|OFF)\s+PK\s+Fc\s+([+-]?\d+(?:\.\d+)?)\s+Hz\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s+dB\s+Q\s+([+-]?\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
        val preamp = preampRegex.find(body)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val bands = body.lineSequence()
            .mapNotNull { line ->
                val match = filterRegex.find(line.trim()) ?: return@mapNotNull null
                ParametricEqBand(
                    id = (match.groupValues[1].toIntOrNull() ?: 1) - 1,
                    enabled = match.groupValues[2].equals("ON", ignoreCase = true),
                    frequencyHz = match.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
                    gainDb = match.groupValues[4].toFloatOrNull() ?: 0f,
                    q = match.groupValues[5].toFloatOrNull() ?: 1f
                )
            }
            .take(10)
            .toList()

        return AutoEqProfile(
            name = summary.name,
            source = summary.source,
            relativePath = summary.relativePath,
            preampDb = preamp,
            bands = bands
        )
    }

    private fun loadIndex(): String {
        val cacheFile = File(context.cacheDir, "autoeq-index.md")
        val maxAgeMs = 24L * 60L * 60L * 1000L
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMs) {
            return cacheFile.readText()
        }
        val content = readUrl(URL("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md"))
        cacheFile.writeText(content)
        return content
    }

    private fun readUrl(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Beatraxus-AutoEq")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
