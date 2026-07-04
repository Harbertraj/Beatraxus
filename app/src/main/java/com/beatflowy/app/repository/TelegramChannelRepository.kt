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
import java.io.FileOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        mime.contains("alac") -> "ALAC"
        mime.contains("dsd") || mime.contains("dsf") -> "DSD"
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
        tdLib.send(TdApi.DownloadFile(thumbnail.file.id, 32, 0, 0, true))
        val path = waitForDownload(tdLib, thumbnail.file.id, 2000) // 2s timeout for thumb
        path?.let { Uri.fromFile(File(it)) }
    } catch (e: Exception) {
        null
    }
}

private suspend fun waitForDownload(tdLib: TdLibManager, fileId: Int, timeoutMs: Long = 5000): String? {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        val file = try { tdLib.send(TdApi.GetFile(fileId)) } catch (e: Exception) { null }
        if (file?.local?.isDownloadingCompleted == true && file.local.path.isNotBlank()) {
            return file.local.path
        }
        delay(200)
    }
    return null
}

private val metadataSemaphore = Semaphore(30) // Increased to 30 for maximum throughput as requested

private data class ExtractedMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: Uri? = null,
    val durationMs: Long? = null
)

private fun parseMetadataFromFileName(fileName: String): Pair<String?, String?> {
    val nameWithoutExt = fileName.substringBeforeLast('.')
    val separators = listOf(" - ", " – ", " — ", " ~ ")
    for (sep in separators) {
        if (nameWithoutExt.contains(sep)) {
            val parts = nameWithoutExt.split(sep, limit = 2)
            if (parts.size == 2) return Pair(parts[0].trim(), parts[1].trim())
        }
    }
    if (nameWithoutExt.count { it == '-' } == 1) {
        val parts = nameWithoutExt.split("-")
        return Pair(parts[0].trim(), parts[1].trim())
    }
    return Pair(null, null)
}

private suspend fun extractFullMetadata(
    context: Context,
    tdLib: TdLibManager,
    fileId: Int,
    fileName: String,
    mimeType: String?,
    totalSize: Long
): ExtractedMetadata = withContext(Dispatchers.IO) {
    if (!tdLib.isReady()) return@withContext ExtractedMetadata()
    try {
        val format = detectAudioFormat(fileName, mimeType)
        val isWav = format == "WAV"
        // Download first 1MB for better chance of getting all metadata + art
        val downloadSize = 1024 * 1024L
        tdLib.send(TdApi.DownloadFile(fileId, 32, 0, downloadSize, true))
        
        if (isWav && totalSize > downloadSize) {
            val footerSize = 1024 * 1024L
            val offset = (totalSize - footerSize).coerceAtLeast(0L)
            tdLib.send(TdApi.DownloadFile(fileId, 32, offset, footerSize, true))
        }
        
        val path = waitForDownload(tdLib, fileId, 3000) ?: return@withContext ExtractedMetadata()
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val artBytes = retriever.embeddedPicture
            
            var albumArtUri: Uri? = null
            if (artBytes != null && artBytes.isNotEmpty()) {
                val outDir = File(context.filesDir, "embedded_album_art").apply { mkdirs() }
                val outFile = File(outDir, "tg_${fileId}.jpg")
                FileOutputStream(outFile).use { it.write(artBytes) }
                albumArtUri = Uri.fromFile(outFile)
            }
            
            ExtractedMetadata(title, artist, album, albumArtUri, duration)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    } catch (e: Exception) {
        ExtractedMetadata()
    }
}

class TelegramChannelRepository(private val context: Context) {
    suspend fun scanChannel(
        tdLib: TdLibManager,
        cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager,
        channelUrl: String,
        existingSongs: Map<String, Song> = emptyMap(),
        onProgress: ((Float) -> Unit)? = null
    ): List<Song> {
        val username = parseTelegramChannelName(channelUrl)
        Log.d("TelegramRepo", "Scanning channel: $username (from $channelUrl)")
        
        val messages = try {
            tdLib.getChannelHistory(username, 500)
        } catch (e: Exception) {
            Log.e("TelegramRepo", "Failed to fetch channel history for: $username", e)
            return emptyList()
        }

        Log.d("TelegramRepo", "Found ${messages.size} messages in channel history")

        val total = messages.size
        var processed = 0

        val semaphore = Semaphore(30)
        val songs = withContext(Dispatchers.IO) {
            messages.map { msg ->
                async {
                    semaphore.withPermit {
                        val audioContent = msg.content as? TdApi.MessageAudio
                        val docContent = msg.content as? TdApi.MessageDocument
                        
                        val songId = "tg_${msg.chatId}_${msg.id}"
                        val existing = existingSongs[songId]
                        
                        val song = if (existing != null) {
                            existing
                        } else if (audioContent != null) {
                            val audio = audioContent.audio
                            val fileId = audio.audio.id
                            
                            val (fnArtist, fnTitle) = parseMetadataFromFileName(audio.fileName)

                            Song(
                                id = songId,
                                uri = Uri.EMPTY,
                                title = audio.title.ifBlank { fnTitle ?: audio.fileName },
                                artist = audio.performer.ifBlank { fnArtist ?: "Unknown Artist" },
                                album = "Telegram: $username",
                                folder = "Telegram: $username",
                                durationMs = (audio.duration * 1000L),
                                format = detectAudioFormat(audio.fileName, audio.mimeType),
                                sampleRateHz = 44100,
                                fileSizeBytes = audio.audio.size.toLong(),
                                source = SongSource.TELEGRAM,
                                albumArtUri = downloadAlbumArtUri(tdLib, audio),
                                telegramChannelUrl = channelUrl,
                                telegramChatId = msg.chatId,
                                telegramMessageId = msg.id,
                                telegramFileId = fileId,
                                isEnriched = false,
                                lastSyncTimestamp = System.currentTimeMillis()
                            )
                        } else if (docContent != null && isAudioMime(docContent.document.mimeType, docContent.document.fileName)) {
                            val doc = docContent.document
                            val fileId = doc.document.id
                            
                            val (fnArtist, fnTitle) = parseMetadataFromFileName(doc.fileName)

                            Song(
                                id = songId,
                                uri = Uri.EMPTY,
                                title = fnTitle ?: doc.fileName,
                                artist = fnArtist ?: "Unknown Artist",
                                album = "Telegram: $username",
                                folder = "Telegram: $username",
                                durationMs = 0L,
                                format = detectAudioFormat(doc.fileName, doc.mimeType),
                                sampleRateHz = 44100,
                                fileSizeBytes = doc.document.size.toLong(),
                                source = SongSource.TELEGRAM,
                                albumArtUri = null,
                                telegramChannelUrl = channelUrl,
                                telegramChatId = msg.chatId,
                                telegramMessageId = msg.id,
                                telegramFileId = fileId,
                                isEnriched = false,
                                lastSyncTimestamp = System.currentTimeMillis()
                            )
                        } else null

                        processed++
                        if (total > 0) onProgress?.invoke(processed.toFloat() / total)
                        song
                    }
                }
            }.awaitAll().filterNotNull()
        }

        Log.d("TelegramRepo", "Successfully parsed ${songs.size} songs from channel $username")
        return songs
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
        cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager,
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
                        
                        val localUri = Uri.EMPTY
                        val realFormat = detectAudioFormat(audio.fileName, audio.mimeType)

                        val (fnArtist, fnTitle) = parseMetadataFromFileName(audio.fileName)
                        val extracted = extractFullMetadata(context, tdLib, fileId, audio.fileName, audio.mimeType, audio.audio.size.toLong())

                        val song = Song(
                            id = "tg_${chatId}_${msg.id}",
                            uri = localUri,
                            title = extracted.title ?: audio.title.ifBlank { fnTitle ?: audio.fileName },
                            artist = extracted.artist ?: audio.performer.ifBlank { fnArtist ?: "Unknown Artist" },
                            album = extracted.album ?: "Telegram: $username",
                            folder = "Telegram: $username",
                            durationMs = extracted.durationMs ?: (audio.duration * 1000L),
                            format = realFormat,
                            sampleRateHz = 44100,
                            fileSizeBytes = audio.audio.size.toLong(),
                            source = SongSource.TELEGRAM,
                            albumArtUri = extracted.albumArtUri ?: downloadAlbumArtUri(tdLib, audio),
                            telegramChannelUrl = channelUrl,
                            telegramChatId = chatId,
                            telegramMessageId = msg.id,
                            telegramFileId = fileId,
                            isEnriched = true,
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
