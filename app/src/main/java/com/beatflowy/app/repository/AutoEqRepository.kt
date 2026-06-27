package com.beatflowy.app.repository

import android.content.Context
import com.beatflowy.app.model.AutoEqProfile
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import org.json.JSONArray
import java.util.Locale

class AutoEqRepository(private val context: Context) {

    // Loaded once and cached — no network calls
    private val profiles: List<AutoEqProfile> by lazy { loadFromAssets().sortedBy { it.name.lowercase(Locale.US) } }

    fun searchProfiles(query: String, limit: Int = 10000): List<AutoEqProfileSummary> {
        val normalized = query.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return profiles.map {
            AutoEqProfileSummary(it.name, it.relativePath, it.source, it.bands)
        }.take(limit)

        return profiles
            .filter { it.name.lowercase(Locale.US).contains(normalized) }
            .map { AutoEqProfileSummary(it.name, it.relativePath, it.source, it.bands) }
            .take(limit)
    }

    // Returns immediately — no suspend needed since it's from assets (fast)
    fun loadProfile(summary: AutoEqProfileSummary): AutoEqProfile {
        return profiles.firstOrNull { it.name == summary.name }
            ?: AutoEqProfile(name = summary.name, relativePath = summary.relativePath)
    }

    private fun loadFromAssets(): List<AutoEqProfile> {
        return try {
            val json = context.assets.open("autoeq_profiles.json")
                .bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            val result = mutableListOf<AutoEqProfile>()
            for (i in 0 until array.length()) {
                try {
                    val obj = array.getJSONObject(i)
                    val bandsArray = obj.getJSONArray("bands")
                    val bands = List(bandsArray.length()) { j ->
                        val band = bandsArray.getJSONObject(j)
                        ParametricEqBand(
                            id = j,
                            enabled = true,
                            frequencyHz = band.optDouble("freq", 0.0).toFloat(),
                            gainDb = band.optDouble("gain", 0.0).toFloat(),
                            q = band.optDouble("q", 1.0).toFloat()
                        )
                    }
                    result.add(
                        AutoEqProfile(
                            name = obj.optString("name", "Unknown"),
                            source = "LOCAL",
                            relativePath = obj.optString("name", ""),
                            preampDb = obj.optDouble("preampDb", 0.0).toFloat(),
                            bands = bands
                        )
                    )
                } catch (e: Exception) {
                    // Skip malformed entries
                    continue
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
}
