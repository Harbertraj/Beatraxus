package com.beatflowy.app.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.model.TelegramChannel
import com.beatflowy.app.model.parseTelegramChannelName
import com.beatflowy.app.telegram.TdLibManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

private val Context.telegramDataStore: DataStore<Preferences> by preferencesDataStore(name = "telegram_channels")

class TelegramChannelRepository(private val context: Context) {
    suspend fun scanChannel(
        tdLib: TdLibManager,
        channelUrl: String,
        onSongsFound: (List<Song>) -> Unit
    ) {
        val username = parseTelegramChannelName(channelUrl)
        val chat = tdLib.send(TdApi.SearchPublicChat(username))
        tdLib.send(TdApi.JoinChat(chat.id))

        var fromMessageId = 0L
        var pageCount = 0
        while (true) {
            val page = tdLib.send(TdApi.GetChatHistory(chat.id, fromMessageId, 0, 100, false))
            if (page.messages.isEmpty()) break
            
            val songs = page.messages.mapNotNull { msg ->
                (msg.content as? TdApi.MessageAudio)?.let { audioContent ->
                    val audio = audioContent.audio
                    Song(
                        id = "tg_${chat.id}_${msg.id}",
                        uri = Uri.EMPTY,
                        title = audio.title.ifBlank { audio.fileName },
                        artist = audio.performer.ifBlank { "Unknown Artist" },
                        album = "Telegram: $username",
                        folder = "Telegram: $username",
                        durationMs = audio.duration * 1000L,
                        format = "MP3",
                        sampleRateHz = 44100,
                        fileSizeBytes = audio.audio.size.toLong(),
                        source = SongSource.TELEGRAM,
                        telegramChannelUrl = channelUrl,
                        telegramChatId = chat.id,
                        telegramMessageId = msg.id,
                        telegramFileId = audio.audio.id,
                        isEnriched = false,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                }
            }
            
            if (songs.isNotEmpty()) {
                onSongsFound(songs)
            }
            
            fromMessageId = page.messages.last().id
            pageCount++
            
            // Limit deep scan if needed, or just keep going in background
            if (pageCount >= 50) break // Safety break for now
        }
    }

    fun observeLiveChannel(
        tdLib: TdLibManager,
        chatId: Long,
        channelUrl: String,
        scope: CoroutineScope,
        onNewSong: (Song) -> Unit
    ) {
        val username = parseTelegramChannelName(channelUrl)
        scope.launch {
            tdLib.updates.collect { update ->
                if (update is TdApi.UpdateNewMessage && update.message.chatId == chatId) {
                    (update.message.content as? TdApi.MessageAudio)?.let { audioContent ->
                        val audio = audioContent.audio
                        val song = Song(
                            id = "tg_${chatId}_${update.message.id}",
                            uri = Uri.EMPTY,
                            title = audio.title.ifBlank { audio.fileName },
                            artist = audio.performer.ifBlank { "Unknown Artist" },
                            album = "Telegram: $username",
                            folder = "Telegram: $username",
                            durationMs = audio.duration * 1000L,
                            format = "MP3",
                            sampleRateHz = 44100,
                            fileSizeBytes = audio.audio.size.toLong(),
                            source = SongSource.TELEGRAM,
                            telegramChannelUrl = channelUrl,
                            telegramChatId = chatId,
                            telegramMessageId = update.message.id,
                            telegramFileId = audio.audio.id,
                            isEnriched = false,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                        onNewSong(song)
                    }
                }
            }
        }
    }

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
