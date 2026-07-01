package com.beatflowy.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.model.TelegramChannel
import com.beatflowy.app.model.parseTelegramChannelName
import com.beatflowy.app.telegram.TdLibManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.FileOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

private val Context.telegramDataStore: DataStore<Preferences> by preferencesDataStore(name = "telegram_channels")

internal fun detectAudioFormat(fileName: String, mimeType: String?): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    when (ext) {
        "wav", "wave" -> return "WAV"
        "flac" -> return "FLAC"
        "m4a", "mp4" -> return "M4A"
        "aac" -> return "AAC"
        "ogg" -> return "OGG"
        "opus" -> return "OPUS"
        "alac" -> return "ALAC"
        "aiff", "aif" -> return "AIFF"
        "dsf", "dsd" -> return "DSD"
        "mp3" -> return "MP3"
    }

    val mime = mimeType?.lowercase().orEmpty()
    return when {
        mime.contains("wav") -> "WAV"
        mime.contains("flac") -> "FLAC"
        mime.contains("mp4") || mime.contains("m4a") -> "M4A"
        mime.contains("aac") -> "AAC"
        mime.contains("ogg") -> "OGG"
        mime.contains("opus") -> "OPUS"
        mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
        else -> "MP3"
    }
}

private suspend fun downloadAlbumArtUri(tdLib: TdLibManager, audio: TdApi.Audio): Uri? {
    if (!tdLib.isReady()) return null
    val thumbnail = audio.albumCoverThumbnail ?: return null
    return try {
        val file = tdLib.send(TdApi.DownloadFile(thumbnail.file.id, 32, 0, 0, true))
        if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
            Uri.fromFile(File(file.local.path))
        } else null
    } catch (e: Exception) {
        null
    }
}

private suspend fun waitForDownload(tdLib: TdLibManager, fileId: Int, timeoutMs: Long = 5000): String? {
    var attempts = 0
    while (attempts * 100 < timeoutMs) {
        val file = try { tdLib.send(TdApi.GetFile(fileId)) } catch (e: Exception) { null }
        if (file?.local?.path?.isNotBlank() == true) return file.local.path
        delay(100)
        attempts++
    }
    return null
}

private suspend fun extractAlbumTag(tdLib: TdLibManager, fileId: Int): String? =
    withContext(Dispatchers.IO) {
        if (!tdLib.isReady()) return@withContext null
        try {
            tdLib.send(TdApi.DownloadFile(fileId, 32, 0, 512 * 1024, true))
            val path = waitForDownload(tdLib, fileId) ?: return@withContext null

            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            null
        }
    }

private suspend fun extractEmbeddedAlbumArt(
    context: Context, 
    tdLib: TdLibManager, 
    fileId: Int, 
    fileName: String, 
    mimeType: String?, 
    totalSize: Long
): Uri? =
    withContext(Dispatchers.IO) {
        if (!tdLib.isReady()) return@withContext null
        try {
            val format = detectAudioFormat(fileName, mimeType)
            val isWav = format == "WAV"
            
            tdLib.send(TdApi.DownloadFile(fileId, 32, 0, 512 * 1024, true))
            
            if (isWav && totalSize > 1024 * 1024) {
                val footerSize = 1024 * 1024L
                val offset = (totalSize - footerSize).coerceAtLeast(0L)
                tdLib.send(TdApi.DownloadFile(fileId, 32, offset, footerSize, true))
            }
            
            val path = waitForDownload(tdLib, fileId) ?: return@withContext null

            val retriever = android.media.MediaMetadataRetriever()
            var artBytes: ByteArray? = null
            try {
                retriever.setDataSource(path)
                artBytes = retriever.embeddedPicture
            } catch (e: Exception) {
                // ignore
            } finally {
                retriever.release()
            }

            if (artBytes != null) {
                val outDir = File(context.filesDir, "embedded_album_art").apply { mkdirs() }
                val outFile = File(outDir, "tg_${fileId}.jpg")
                try {
                    FileOutputStream(outFile).use { it.write(artBytes) }
                    return@withContext Uri.fromFile(outFile)
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        null
    }

class TelegramChannelRepository(private val context: Context) {
    suspend fun scanChannel(
        tdLib: TdLibManager,
        channelUrl: String,
        onSongsFound: (List<Song>) -> Unit
    ) {
        val username = parseTelegramChannelName(channelUrl)
        val chat = try {
            tdLib.send(TdApi.SearchPublicChat(username))
        } catch (e: Exception) {
            Log.e("TelegramRepo", "Failed to search chat: $username", e)
            return
        }
        
        try {
            tdLib.send(TdApi.JoinChat(chat.id))
        } catch (e: Exception) {
            Log.w("TelegramRepo", "Failed to join chat: ${chat.id}", e)
        }

        var fromMessageId = 0L
        var pageCount = 0
        while (true) {
            val page = try {
                tdLib.send(TdApi.GetChatHistory(chat.id, fromMessageId, 0, 100, false))
            } catch (e: Exception) {
                Log.e("TelegramRepo", "GetChatHistory failed", e)
                break
            }
            if (page.messages.isEmpty()) break

            val songs = withContext(Dispatchers.IO) {
                page.messages.map { msg ->
                    async {
                        val audioContent = msg.content as? TdApi.MessageAudio
                        val docContent = msg.content as? TdApi.MessageDocument
                        
                        if (audioContent != null) {
                            val audio = audioContent.audio
                            val fileId = audio.audio.id
                            val realAlbum = extractAlbumTag(tdLib, fileId)
                            Song(
                                id = "tg_${chat.id}_${msg.id}",
                                uri = Uri.EMPTY,
                                title = audio.title.ifBlank { audio.fileName },
                                artist = audio.performer.ifBlank { "Unknown Artist" },
                                album = realAlbum ?: "Telegram: $username",
                                folder = "Telegram: $username",
                                durationMs = audio.duration * 1000L,
                                format = detectAudioFormat(audio.fileName, audio.mimeType),
                                sampleRateHz = 44100,
                                fileSizeBytes = audio.audio.size.toLong(),
                                source = SongSource.TELEGRAM,
                                albumArtUri = extractEmbeddedAlbumArt(context, tdLib, fileId, audio.fileName, audio.mimeType, audio.audio.size.toLong()) ?: downloadAlbumArtUri(tdLib, audio),
                                telegramChannelUrl = channelUrl,
                                telegramChatId = chat.id,
                                telegramMessageId = msg.id,
                                telegramFileId = fileId,
                                isEnriched = false,
                                lastSyncTimestamp = System.currentTimeMillis()
                            )
                        } else if (docContent != null && isAudioMime(docContent.document.mimeType, docContent.document.fileName)) {
                            val doc = docContent.document
                            val fileId = doc.document.id
                            val realAlbum = extractAlbumTag(tdLib, fileId)
                            Song(
                                id = "tg_${chat.id}_${msg.id}",
                                uri = Uri.EMPTY,
                                title = doc.fileName,
                                artist = "Unknown Artist",
                                album = realAlbum ?: "Telegram: $username",
                                folder = "Telegram: $username",
                                durationMs = 0,
                                format = detectAudioFormat(doc.fileName, doc.mimeType),
                                sampleRateHz = 44100,
                                fileSizeBytes = doc.document.size.toLong(),
                                source = SongSource.TELEGRAM,
                                albumArtUri = extractEmbeddedAlbumArt(context, tdLib, fileId, doc.fileName, doc.mimeType, doc.document.size.toLong()),
                                telegramChannelUrl = channelUrl,
                                telegramChatId = chat.id,
                                telegramMessageId = msg.id,
                                telegramFileId = fileId,
                                isEnriched = false,
                                lastSyncTimestamp = System.currentTimeMillis()
                            )
                        } else null
                    }
                }.awaitAll().filterNotNull()
            }

            if (songs.isNotEmpty()) {
                onSongsFound(songs)
            }

            fromMessageId = page.messages.last().id
            pageCount++
            if (pageCount >= 50) break
        }
    }

    private fun isAudioMime(mime: String?, fileName: String): Boolean {
        val m = mime?.lowercase() ?: ""
        val f = fileName.lowercase()
        return m.startsWith("audio/") || 
               f.endsWith(".flac") || f.endsWith(".wav") || f.endsWith(".mp3") || 
               f.endsWith(".m4a") || f.endsWith(".alac") || f.endsWith(".ogg") || 
               f.endsWith(".opus") || f.endsWith(".dsf") || f.endsWith(".aiff")
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
                    val msg = update.message
                    val audioContent = msg.content as? TdApi.MessageAudio
                    if (audioContent != null) {
                        val audio = audioContent.audio
                        val fileId = audio.audio.id
                        val realAlbum = extractAlbumTag(tdLib, fileId)
                        val song = Song(
                            id = "tg_${chatId}_${msg.id}",
                            uri = Uri.EMPTY,
                            title = audio.title.ifBlank { audio.fileName },
                            artist = audio.performer.ifBlank { "Unknown Artist" },
                            album = realAlbum ?: "Telegram: $username",
                            folder = "Telegram: $username",
                            durationMs = audio.duration * 1000L,
                            format = detectAudioFormat(audio.fileName, audio.mimeType),
                            sampleRateHz = 44100,
                            fileSizeBytes = audio.audio.size.toLong(),
                            source = SongSource.TELEGRAM,
                            albumArtUri = extractEmbeddedAlbumArt(context, tdLib, fileId, audio.fileName, audio.mimeType, audio.audio.size.toLong()) ?: downloadAlbumArtUri(tdLib, audio),
                            telegramChannelUrl = channelUrl,
                            telegramChatId = chatId,
                            telegramMessageId = msg.id,
                            telegramFileId = fileId,
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
