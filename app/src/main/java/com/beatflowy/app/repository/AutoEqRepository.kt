package com.beatflowy.app.repository

import android.content.Context
import com.beatflowy.app.model.AutoEqProfile
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import org.json.JSONArray
import java.util.Locale

class AutoEqRepository(private val context: Context) {

    // Loaded once and cached — no network calls
    private val profiles: List<AutoEqProfile> by lazy { loadFromAssets() }

    fun searchProfiles(query: String, limit: Int = 12): List<AutoEqProfileSummary> {
        val normalized = query.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return profiles.map {
            AutoEqProfileSummary(it.name, it.relativePath, it.source)
        }.take(limit)

        return profiles
            .filter { it.name.lowercase(Locale.US).contains(normalized) }
            .map { AutoEqProfileSummary(it.name, it.relativePath, it.source) }
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
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                val bandsArray = obj.getJSONArray("bands")
                val bands = List(bandsArray.length()) { j ->
                    val band = bandsArray.getJSONObject(j)
                    ParametricEqBand(
                        id = j,
                        enabled = true,
                        frequencyHz = band.getDouble("freq").toFloat(),
                        gainDb = band.getDouble("gain").toFloat(),
                        q = band.getDouble("q").toFloat()
                    )
                }
                AutoEqProfile(
                    name = obj.getString("name"),
                    source = "LOCAL",
                    relativePath = obj.getString("name"),
                    preampDb = obj.getDouble("preampDb").toFloat(),
                    bands = bands
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
