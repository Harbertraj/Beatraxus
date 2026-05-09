package com.beatflowy.app.repository

import android.content.Context
import com.beatflowy.app.model.AutoEqProfile
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoEqApiService(private val context: Context) {

    private val userAgent = "BeatflowApp/1.0"
    private val baseUrl = "https://api.github.com/repos/jaakkopasanen/AutoEq/contents/results"
    private val rawUrlBase = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"
    
    // In-memory cache for directory listing
    private var cachedDirectories: List<String>? = null
    private var cachedSummaries: MutableMap<String, List<AutoEqProfileSummary>> = mutableMapOf()

    suspend fun searchProfiles(query: String): List<AutoEqProfileSummary> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        
        try {
            val sources = getMeasurementSources()
            val results = mutableListOf<AutoEqProfileSummary>()
            
            for (source in sources) {
                val sourceFolders = getFoldersInSource(source)
                sourceFolders.filter { it.contains(query, ignoreCase = true) }.forEach { name ->
                    results.add(
                        AutoEqProfileSummary(
                            name = name,
                            source = "GITHUB:$source",
                            relativePath = "results/$source/$name"
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getMeasurementSources(): List<String> {
        cachedDirectories?.let { return it }
        
        return try {
            val conn = URL(baseUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (item.getString("type") == "dir") {
                        list.add(item.getString("name"))
                    }
                }
                cachedDirectories = list
                list
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getFoldersInSource(source: String): List<String> {
        cachedSummaries[source]?.let { summaries -> return summaries.map { it.name } }
        
        return try {
            val conn = URL("$baseUrl/$source").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (item.getString("type") == "dir") {
                        list.add(item.getString("name"))
                    }
                }
                // We don't populate cachedSummaries fully here to save memory, 
                // just return the names for filtering.
                list
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchProfile(summary: AutoEqProfileSummary): AutoEqProfile? = withContext(Dispatchers.IO) {
        val cacheKey = sha256(summary.relativePath)
        val cached = getFromDiskCache(cacheKey)
        if (cached != null) return@withContext cached

        try {
            val url = "$rawUrlBase/${summary.relativePath}/ParametricEQ.txt"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val profile = parseParametricEq(summary, text)
                if (profile != null) {
                    saveToDiskCache(cacheKey, profile)
                }
                profile
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseParametricEq(summary: AutoEqProfileSummary, text: String): AutoEqProfile? {
        try {
            var preampDb = 0f
            val bands = mutableListOf<ParametricEqBand>()
            val lines = text.lines()
            
            var bandId = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("Preamp:")) {
                    preampDb = trimmed.substringAfter("Preamp:").substringBefore("dB").trim().toFloatOrNull() ?: 0f
                } else if (trimmed.startsWith("Filter")) {
                    // Format: Filter 1: ON PK Fc 31 Hz Gain 5.9 dB Q 0.60
                    val parts = trimmed.split(" ")
                    val fcIdx = parts.indexOf("Fc")
                    val gainIdx = parts.indexOf("Gain")
                    val qIdx = parts.indexOf("Q")
                    
                    if (fcIdx != -1 && gainIdx != -1 && qIdx != -1) {
                        val freq = parts[fcIdx + 1].toFloatOrNull() ?: continue
                        val gain = parts[gainIdx + 1].toFloatOrNull() ?: continue
                        val q = parts[qIdx + 1].toFloatOrNull() ?: continue
                        
                        bands.add(ParametricEqBand(
                            id = bandId++,
                            enabled = true,
                            frequencyHz = freq,
                            gainDb = gain,
                            q = q
                        ))
                    }
                }
            }
            
            if (bands.isEmpty()) return null
            
            return AutoEqProfile(
                name = summary.name,
                source = summary.source,
                relativePath = summary.relativePath,
                preampDb = preampDb,
                bands = bands
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getFromDiskCache(key: String): AutoEqProfile? {
        val cacheFile = File(context.cacheDir, "autoeq/$key.json")
        if (!cacheFile.exists()) return null
        
        // 7 days TTL
        if (System.currentTimeMillis() - cacheFile.lastModified() > 7 * 24 * 60 * 60 * 1000) {
            cacheFile.delete()
            return null
        }

        return try {
            val json = JSONObject(cacheFile.readText())
            val bandsArray = json.getJSONArray("bands")
            val bands = List(bandsArray.length()) { i ->
                val b = bandsArray.getJSONObject(i)
                ParametricEqBand(
                    id = i,
                    enabled = true,
                    frequencyHz = b.getDouble("freq").toFloat(),
                    gainDb = b.getDouble("gain").toFloat(),
                    q = b.getDouble("q").toFloat()
                )
            }
            AutoEqProfile(
                name = json.getString("name"),
                source = json.getString("source"),
                relativePath = json.getString("relativePath"),
                preampDb = json.getDouble("preampDb").toFloat(),
                bands = bands
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDiskCache(key: String, profile: AutoEqProfile) {
        try {
            val dir = File(context.cacheDir, "autoeq")
            if (!dir.exists()) dir.mkdirs()
            
            val json = JSONObject().apply {
                put("name", profile.name)
                put("source", profile.source)
                put("relativePath", profile.relativePath)
                put("preampDb", profile.preampDb.toDouble())
                val bandsArray = JSONArray()
                profile.bands.forEach { band ->
                    bandsArray.put(JSONObject().apply {
                        put("freq", band.frequencyHz.toDouble())
                        put("gain", band.gainDb.toDouble())
                        put("q", band.q.toDouble())
                    })
                }
                put("bands", bandsArray)
            }
            
            File(dir, "$key.json").writeText(json.toString())
        } catch (e: Exception) {}
    }
}
