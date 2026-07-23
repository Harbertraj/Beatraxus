package com.beatraxus.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.TelegramChannel
import com.beatraxus.app.model.parseTelegramChannelName
import com.beatraxus.app.telegram.TdLibManager
import java.io.FileOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode

private val Context.telegramDataStore: DataStore<Preferences> by preferencesDataStore(name = "telegram_channels")

internal fun detectAudioFormat(fileName: String, mimeType: String?): String {
    val f = fileName.lowercase()
    if (f.contains("alac")) return "ALAC"
    
    val ext = f.substringAfterLast('.', "").lowercase()
    when (ext) {
        "wav", "wave", "bwf" -> return "WAV"
        "flac" -> return "FLAC"
        "m4a", "mp4" -> return "M4A"
        "aac" -> return "AAC"
        "ogg" -> return "OGG"
        "opus" -> return "OPUS"
        "alac", "caf" -> return "ALAC"
        "aiff", "aif" -> return "AIFF"
        "dsf", "dsd" -> return "DSD"
        "ac3", "eac3", "ec3", "dts" -> return "DOLBY"
        "mp3" -> return "MP3"
    }

    val mime = mimeType?.lowercase().orEmpty()
    return when {
        mime.contains("alac") -> "ALAC"
        mime.contains("flac") -> "FLAC"
        mime.contains("wav") || mime.contains("wave") -> "WAV"
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
        val path = tdLib.waitForFile(thumbnail.file.id, timeoutMs = 2000) // 2s timeout for thumb
        path?.let { Uri.fromFile(File(it)) }
    } catch (e: Exception) {
        null
    }
}

private val metadataSemaphore = Semaphore(50) // Increased to 50 for maximum throughput as requested

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
        val isM4A = format == "M4A" || format == "ALAC"
        
        // Download first 1MB for better chance of getting all metadata + art
        val downloadSize = 1024 * 1024L
        tdLib.send(TdApi.DownloadFile(fileId, 32, 0, downloadSize, true))
        
        // For WAV and M4A/ALAC, we often need the footer for metadata/duration
        if ((isWav || isM4A) && totalSize > downloadSize) {
            val footerSize = if (isWav) 8 * 1024 * 1024L else 1024 * 1024L // 8MB for WAV art
            val offset = (totalSize - footerSize).coerceAtLeast(0L)
            tdLib.send(TdApi.DownloadFile(fileId, 32, offset, footerSize, true))
        }
        
        val path = tdLib.waitForFile(fileId, downloadSize = downloadSize, timeoutMs = 10000) ?: return@withContext ExtractedMetadata()
        
        // Guard against ALAC files which cause native crashes in MediaMetadataRetriever on some devices
        if (format == "ALAC") {
            Log.d("TelegramRepo", "Skipping MediaMetadataRetriever for ALAC file: $fileName")
            return@withContext extractMetadataWithFfprobe(path)
        }

        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            
            var finalArtBytes = retriever.embeddedPicture
            
            // Special handling for WAV art
            if ((finalArtBytes == null || finalArtBytes.isEmpty()) && isWav) {
                finalArtBytes = WavArtHelper.extractArt(path)
            }
            
            var albumArtUri: Uri? = null
            if (finalArtBytes != null && finalArtBytes.isNotEmpty()) {
                val outDir = File(context.filesDir, "embedded_album_art").apply { mkdirs() }
                val outFile = File(outDir, "tg_${fileId}.jpg")
                FileOutputStream(outFile).use { it.write(finalArtBytes) }
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

private fun extractMetadataWithFfprobe(path: String): ExtractedMetadata {
    val session = FFprobeKit.execute("-v quiet -print_format json -show_format -show_streams $path")
    if (ReturnCode.isSuccess(session.returnCode)) {
        try {
            val json = JSONObject(session.output ?: "{}")
            val formatJson = json.optJSONObject("format")
            val tags = formatJson?.optJSONObject("tags")
            val duration = (formatJson?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1000.0

            fun getTag(vararg keys: String): String? {
                for (key in keys) {
                    val value = tags?.optString(key).takeIf { !it.isNullOrBlank() }
                    if (value != null) return value
                }
                return null
            }

            return ExtractedMetadata(
                title = getTag("title", "TITLE"),
                artist = getTag("artist", "ARTIST"),
                album = getTag("album", "ALBUM"),
                durationMs = duration.toLong()
            )
        } catch (e: Exception) {
            // ignore
        }
    }
    return ExtractedMetadata()
}

class TelegramChannelRepository(private val context: Context) {
    suspend fun scanChannel(
        tdLib: TdLibManager,
        cloudCacheManager: com.beatraxus.app.drive.CloudCacheManager,
        channelUrl: String,
        existingSongs: Map<String, Song> = emptyMap(),
        allowedFormats: Set<String>? = null,
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

        val semaphore = Semaphore(50)
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
                            
                            val format = detectAudioFormat(audio.fileName, audio.mimeType)
                            if (allowedFormats != null && allowedFormats.isNotEmpty() && !allowedFormats.contains(format.uppercase())) {
                                processed++
                                if (total > 0) onProgress?.invoke(processed.toFloat() / total)
                                return@withPermit null
                            }

                            val (fnArtist, fnTitle) = parseMetadataFromFileName(audio.fileName)

                            Song(
                                id = songId,
                                uri = Uri.EMPTY,
                                title = audio.title.ifBlank { fnTitle ?: audio.fileName },
                                artist = audio.performer.ifBlank { fnArtist ?: "Unknown Artist" },
                                album = "Telegram: $username",
                                folder = "Telegram: $username",
                                durationMs = (audio.duration * 1000L),
                                format = format,
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
                            
                            val format = detectAudioFormat(doc.fileName, doc.mimeType)
                            if (allowedFormats != null && allowedFormats.isNotEmpty() && !allowedFormats.contains(format.uppercase())) {
                                processed++
                                if (total > 0) onProgress?.invoke(processed.toFloat() / total)
                                return@withPermit null
                            }

                            val (fnArtist, fnTitle) = parseMetadataFromFileName(doc.fileName)

                            Song(
                                id = songId,
                                uri = Uri.EMPTY,
                                title = fnTitle ?: doc.fileName,
                                artist = fnArtist ?: "Unknown Artist",
                                album = "Telegram: $username",
                                folder = "Telegram: $username",
                                durationMs = 0L,
                                format = format,
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
               f.endsWith(".opus") || f.endsWith(".dsf") || f.endsWith(".aiff") ||
               f.endsWith(".caf") || f.endsWith(".bwf")
    }

    fun observeLiveChannel(
        tdLib: TdLibManager,
        cloudCacheManager: com.beatraxus.app.drive.CloudCacheManager,
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
                    val docContent = msg.content as? TdApi.MessageDocument
                    
                    if (audioContent != null || (docContent != null && isAudioMime(docContent.document.mimeType, docContent.document.fileName))) {
                        val fileId = audioContent?.audio?.audio?.id ?: docContent!!.document.document.id
                        val fileName = audioContent?.audio?.fileName ?: docContent!!.document.fileName
                        val mimeType = audioContent?.audio?.mimeType ?: docContent!!.document.mimeType
                        val fileSize = audioContent?.audio?.audio?.size?.toLong() ?: docContent!!.document.document.size.toLong()
                        val duration = audioContent?.audio?.duration?.times(1000L) ?: 0L
                        
                        val localUri = Uri.EMPTY
                        val realFormat = detectAudioFormat(fileName, mimeType)

                        val (fnArtist, fnTitle) = parseMetadataFromFileName(fileName)
                        val extracted = extractFullMetadata(context, tdLib, fileId, fileName, mimeType, fileSize)

                        val song = Song(
                            id = "tg_${chatId}_${msg.id}",
                            uri = localUri,
                            title = extracted.title ?: audioContent?.audio?.title?.ifBlank { fnTitle ?: fileName } ?: (fnTitle ?: fileName),
                            artist = extracted.artist ?: audioContent?.audio?.performer?.ifBlank { fnArtist ?: "Unknown Artist" } ?: (fnArtist ?: "Unknown Artist"),
                            album = extracted.album ?: "Telegram: $username",
                            folder = "Telegram: $username",
                            durationMs = extracted.durationMs ?: duration,
                            format = realFormat,
                            sampleRateHz = 44100,
                            fileSizeBytes = fileSize,
                            source = SongSource.TELEGRAM,
                            albumArtUri = extracted.albumArtUri ?: audioContent?.let { downloadAlbumArtUri(tdLib, it.audio) },
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
                    is String -> preferences[stringPreferencesKey(keyName)] = value
                    is Boolean -> preferences[booleanPreferencesKey(keyName)] = value
                    is Int -> preferences[intPreferencesKey(keyName)] = value
                    is Long -> preferences[longPreferencesKey(keyName)] = value
                    is Float -> preferences[floatPreferencesKey(keyName)] = value
                    is Double -> preferences[floatPreferencesKey(keyName)] = value.toFloat()
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
                addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                lastSyncTimestamp = obj.optLong("lastSyncTimestamp", 0L)
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
                put("lastSyncTimestamp", 0L)
            }.toString()
            prefs[TELEGRAM_CHANNELS] = (filtered + json).toSet()
        }
    }

    suspend fun updateLastSyncTimestamp(url: String, timestamp: Long) {
        context.telegramDataStore.edit { prefs ->
            val current = prefs[TELEGRAM_CHANNELS] ?: emptySet()
            val updated = current.map { json ->
                val obj = JSONObject(json)
                if (obj.getString("url") == url) {
                    obj.put("lastSyncTimestamp", timestamp)
                    obj.toString()
                } else {
                    json
                }
            }.toSet()
            prefs[TELEGRAM_CHANNELS] = updated
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
