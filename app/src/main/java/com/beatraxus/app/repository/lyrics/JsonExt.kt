package com.beatraxus.app.repository.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Null-safe Gson accessors for provider responses.
 *
 * `JsonObject.get(key)?.asString` is NOT null-safe: when the API sends `"field": null` the value is a
 * `JsonNull`, and `JsonNull.asString` throws. `getAsJsonArray` / `getAsJsonObject` throw a
 * ClassCastException on JsonNull too. A single null field (e.g. `"richSyncLyrics": null`) used to make a
 * provider throw inside its try/catch and return "no lyrics" for a song that actually had them.
 */
internal fun JsonObject.str(key: String): String? {
    val el = get(key) ?: return null
    if (!el.isJsonPrimitive) return null
    return runCatching { el.asString }.getOrNull()
}

internal fun JsonObject.int(key: String): Int? {
    val el = get(key) ?: return null
    if (!el.isJsonPrimitive) return null
    return runCatching { el.asDouble.toInt() }.getOrNull()
}

internal fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** `success` may be missing or null on some deployments; only an explicit `false` means failure. */
internal fun JsonObject.isSuccessful(): Boolean {
    val el: JsonElement = get("success") ?: return true
    if (!el.isJsonPrimitive) return true
    return runCatching { el.asBoolean }.getOrDefault(true)
}
