package com.beatraxus.app.addons

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** "Continue watching" list for OTT addons (last 20 titles opened for playback, per addon). */
object OttHistory {
    private const val MAX = 20
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("beatraxus_ott_history", Context.MODE_PRIVATE)

    fun add(context: Context, addonId: String, t: OttTitle) {
        val list = load(context, addonId).filter { it.id != t.id }.toMutableList()
        list.add(0, t)
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name)
                .put("poster", it.poster ?: "").put("background", it.background ?: "")
                .put("description", it.description ?: "").put("year", it.year ?: "").put("rating", it.rating ?: ""))
        }
        prefs(context).edit().putString(addonId, arr.toString()).apply()
    }

    fun load(context: Context, addonId: String): List<OttTitle> = runCatching {
        val arr = JSONArray(prefs(context).getString(addonId, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OttTitle(o.getString("id"), o.getString("type"), o.getString("name"),
                o.optString("poster").ifBlank { null }, o.optString("background").ifBlank { null },
                o.optString("description").ifBlank { null }, o.optString("year").ifBlank { null }, o.optString("rating").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    fun remove(context: Context, addonId: String, titleId: String) {
        val keep = load(context, addonId).filter { it.id != titleId }
        val arr = JSONArray()
        keep.forEach { arr.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster ?: "").put("background", it.background ?: "").put("description", it.description ?: "").put("year", it.year ?: "").put("rating", it.rating ?: "")) }
        prefs(context).edit().putString(addonId, arr.toString()).apply()
    }
}
