package com.beatflowy.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatflowy.app.model.TelegramChannel
import com.beatflowy.app.model.parseTelegramChannelName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.telegramDataStore: DataStore<Preferences> by preferencesDataStore(name = "telegram_channels")

class TelegramChannelRepository(private val context: Context) {
    suspend fun exportPreferences(): Map<String, Any> {
        return context.telegramDataStore.data.first().asMap().mapKeys { it.key.name }.filterValues { it != null } as Map<String, Any>
    }

    suspend fun importPreferences(map: Map<String, Any>) {
        context.telegramDataStore.edit { preferences ->
            map.forEach { (keyName, value) ->
                when (value) {
                    is List<*> -> {
                        if (value.all { it is String }) {
                            @Suppress("UNCHECKED_CAST")
                            preferences[stringSetPreferencesKey(keyName)] = (value as List<String>).toSet()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val TELEGRAM_CHANNELS = stringSetPreferencesKey("telegram_channels")
    }

    val channels: Flow<List<TelegramChannel>> = context.telegramDataStore.data.map { prefs ->
        (prefs[TELEGRAM_CHANNELS] ?: emptySet()).map { json ->
            val obj = JSONObject(json)
            TelegramChannel(
                url = obj.getString("url"),
                name = obj.getString("name"),
                enabled = obj.optBoolean("enabled", true),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis())
            )
        }.sortedByDescending { it.addedAt }
    }

    suspend fun addChannel(url: String) {
        val name = parseTelegramChannelName(url)
        context.telegramDataStore.edit { prefs ->
            val current = prefs[TELEGRAM_CHANNELS] ?: emptySet()
            // Check if already exists by url to avoid duplicates
            val filtered = current.filter { json ->
                JSONObject(json).getString("url") != url
            }
            val json = JSONObject().apply {
                put("url", url)
                put("name", name)
                put("enabled", true)
                put("addedAt", System.currentTimeMillis())
            }.toString()
            prefs[TELEGRAM_CHANNELS] = (filtered + json).toSet()
        }
    }

    suspend fun toggleChannel(url: String, enabled: Boolean) {
        context.telegramDataStore.edit { prefs ->
            val current = prefs[TELEGRAM_CHANNELS] ?: emptySet()
            val updated = current.map { json ->
                val obj = JSONObject(json)
                if (obj.getString("url") == url) {
                    obj.put("enabled", enabled)
                    obj.toString()
                } else {
                    json
                }
            }.toSet()
            prefs[TELEGRAM_CHANNELS] = updated
        }
    }

    suspend fun removeChannel(url: String) {
        context.telegramDataStore.edit { prefs ->
            val current = prefs[TELEGRAM_CHANNELS] ?: emptySet()
            val filtered = current.filter { json ->
                val obj = JSONObject(json)
                obj.getString("url") != url
            }.toSet()
            prefs[TELEGRAM_CHANNELS] = filtered
        }
    }
}
