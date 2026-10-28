package com.beatraxus.app.repository.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Null-safe Gson navigation for lyric provider responses.
 *
 * `obj.get(k)?.asString` / `?.asLong` / `getAsJsonArray(k)` all THROW when the server sends
 * `"k": null` (JsonNull). Inside a provider's try/catch that turned one null field - e.g. an absent
 * `trackName` or `score` - into "no lyrics found" for a song the server actually returned.
 * Walk objects by key (String) and arrays by index (Int); anything missing or null gives null.
 */
internal fun JsonElement?.jAt(vararg path: Any): JsonElement? {
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

internal fun JsonElement?.jStr(vararg path: Any): String? {
    val el = jAt(*path) ?: return null
    return if (el.isJsonPrimitive) runCatching { el.asString }.getOrNull() else null
}

internal fun JsonElement?.jLong(vararg path: Any): Long? {
    val el = jAt(*path)?.takeIf { it.isJsonPrimitive } ?: return null
    return runCatching { el.asLong }.getOrNull() ?: runCatching { el.asDouble.toLong() }.getOrNull()
}

internal fun JsonElement?.jDouble(vararg path: Any): Double? {
    val el = jAt(*path)?.takeIf { it.isJsonPrimitive } ?: return null
    return runCatching { el.asDouble }.getOrNull()
}

internal fun JsonElement?.jBool(vararg path: Any): Boolean? {
    val el = jAt(*path)?.takeIf { it.isJsonPrimitive } ?: return null
    return runCatching { el.asBoolean }.getOrNull()
}

internal fun JsonElement?.jArr(vararg path: Any): JsonArray? = jAt(*path)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonElement?.jObj(vararg path: Any): JsonObject? = jAt(*path)?.takeIf { it.isJsonObject }?.asJsonObject
