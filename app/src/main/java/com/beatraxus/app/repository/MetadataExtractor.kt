package com.beatraxus.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.SyncQuality
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MetadataExtractor(private val context: Context) {

    private val TAG = "MetadataExtractor"
    private val batchSemaphore = Semaphore(30) // Increased to 30 parallel workers for maximum speed as requested
    private val onlineGenreService = GenreApiService()
    
    // Global tracking to prevent multiple batches from processing the same song
    companion object {
        private val inProgressSongs = ConcurrentHashMap.newKeySet<String>()
    }

    suspend fun extractCloudMetadataBatch(
        songs: List<Song>,
        credential: GoogleAccountCredential,
        onProgress: (suspend (Song) -> Unit)? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val dataSaver = prefs.getBoolean("data_saver_enabled", false)
        val artworkEnabled = prefs.getBoolean("artwork_enrichment_enabled", true)
        val quality = SyncQuality.valueOf(prefs.getString("sync_quality", "MEDIUM") ?: "MEDIUM")

        // Filter out songs that are already being enriched by another process
        val songsToProcess = songs.filter { inProgressSongs.add(it.id) }

        try {
            songsToProcess.map { song ->
                async {
                    batchSemaphore.withPermit {
                        try {
                            val updated = extractCloudMetadata(song, credential, dataSaver, artworkEnabled, quality)
                            onProgress?.invoke(updated)
                            updated
                        } finally {
                            inProgressSongs.remove(song.id)
                        }
                    }
                }
            }.awaitAll()
        } finally {
            // Ensure we clear from global tracking even if cancelled
            songsToProcess.forEach { inProgressSongs.remove(it.id) }
        }
    }

    suspend fun extractCloudMetadata(
        song: Song, 
        credential: GoogleAccountCredential?,
        dataSaver: Boolean = false,
        artworkEnabled: Boolean = true,
        quality: SyncQuality = SyncQuality.MEDIUM
    ): Song = withContext(Dispatchers.IO) {
        val artworkStillMissing = artworkEnabled && song.albumArtUri == null && !song.albumArtFetchAttempted
        if (song.isEnriched && !dataSaver && song.durationMs > 0 && !artworkStillMissing) {
             return@withContext song
        }

        if (song.source != SongSource.GDRIVE || song.driveFileId == null || credential == null) return@withContext song
        
        // Speedup UNDONE: Every song now performs its own full extraction to ensure 100% accuracy
        return@withContext fetchSongSpecificMetadata(song, credential, true, artworkEnabled, quality)
    }

    private suspend fun fetchSongSpecificMetadata(
        song: Song, 
        credential: GoogleAccountCredential,
        fetchArt: Boolean = true,
        artworkEnabled: Boolean = true,
        quality: SyncQuality = SyncQuality.MEDIUM
    ): Song = withContext(Dispatchers.IO) {
        val format = song.format.lowercase()
        val isWav = format.contains("wav")
        val extension = if (format == "audio") "" else ".$format"
        val tempFile = File(context.cacheDir, "metadata_temp_${song.id}$extension")
        
        try {
            // Reverted to full header sizes for accuracy
            val headerSize = when(quality) {
                SyncQuality.LOW -> 1024_000L
                SyncQuality.MEDIUM -> 4_194_304L
                SyncQuality.HIGH -> 8_388_608L
            }

            downloadPart(song.driveFileId!!, tempFile, credential, "bytes=0-${headerSize - 1}", 0L)

            // Step 7: Increased footer size to 8MB to catch larger embedded artwork in WAVs
            if (isWav && song.fileSizeBytes > headerSize) {
                val footerSize = 8_388_608L // Increased from 4MB
                val start = (song.fileSizeBytes - footerSize).coerceAtLeast(0L)
                downloadPart(song.driveFileId, tempFile, credential, "bytes=$start-${song.fileSizeBytes - 1}", start)
            }

            var updatedSong = extractMetadataFromLocalFile(song, tempFile, fetchArt, artworkEnabled)

            // Step 7 Fallback: If art is still missing for a large WAV, download the full file as a last resort
            if (isWav && fetchArt && artworkEnabled && updatedSong.albumArtUri == null && song.fileSizeBytes > (headerSize + 8_388_608L)) {
                Log.i(TAG, "Album art missing for large WAV ${song.title}, triggering full-file fallback (Last Resort)")
                downloadPart(song.driveFileId!!, tempFile, credential, null, 0L)
                updatedSong = extractMetadataFromLocalFile(song, tempFile, fetchArt, artworkEnabled)
            }

            return@withContext updatedSong
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error extracting metadata for ${song.title}", e)
            return@withContext song
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun extractMetadataFromLocalFile(
        song: Song,
        localFile: File,
        fetchArt: Boolean = true,
        artworkEnabled: Boolean = true
    ): Song = withContext(Dispatchers.IO) {
        var updatedSong = song
        val format = song.format.lowercase()
        val isWav = format.contains("wav")
        
        // Guard against ALAC files which cause native crashes in MediaMetadataRetriever/MediaCodec on some devices
        val isAlacPossible = format == "alac" || format == "m4a" || format == "mp4" || format == "audio"
        if (isAlacPossible && isAlacFile(localFile)) {
            updatedSong = updatedSong.copy(format = "ALAC")
            // Skip MediaMetadataRetriever for ALAC to prevent crash
            return@withContext extractMetadataWithFFprobe(updatedSong, localFile)
        }

        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(localFile.absolutePath)
            if (fetchArt && artworkEnabled) {
                val artBytes = retriever.embeddedPicture
                if (artBytes != null && artBytes.isNotEmpty()) {
                    updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, artBytes))
                }
            }

            if (updatedSong.albumArtUri == null && fetchArt && artworkEnabled && (isWav || updatedSong.format == "ALAC")) {
                // FFmpeg fallback for complex containers or missing headers
                val ffmpegArt = extractEmbeddedArtWithFfmpeg(song.id, localFile)
                if (ffmpegArt != null) {
                    updatedSong = updatedSong.copy(albumArtUri = ffmpegArt)
                }
            }

            if (isWav) {
                // First try specialized WAV art extraction (handles ID3 and DISP chunks)
                if (fetchArt && artworkEnabled && updatedSong.albumArtUri == null) {
                    WavArtHelper.extractArt(localFile.absolutePath)?.let { artBytes ->
                        updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, artBytes))
                    }
                }
                updatedSong = extractWavMetadataManual(localFile, updatedSong)
            }

            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val genre = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
            val yearStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)

            updatedSong = updatedSong.copy(
                title = if (title.isNullOrBlank()) updatedSong.title else title,
                artist = if (artist.isNullOrBlank()) updatedSong.artist else artist,
                album = if (album != null && album != "Unknown Album") album else updatedSong.album,
                genre = if (genre.isNullOrBlank()) updatedSong.genre else genre,
                year = yearStr?.toIntOrNull() ?: updatedSong.year,
                durationMs = durationStr?.toLongOrNull() ?: updatedSong.durationMs
            )
        } catch (e: Exception) {
            Log.d(TAG, "MediaMetadataRetriever failed for ${song.title}")
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

        try {
            val audioFile = AudioFileIO.read(localFile)
            val tag = audioFile.tag
            val header = audioFile.audioHeader

            if (tag != null) {
                updatedSong = updatedSong.copy(
                    title = tag.getFirst(FieldKey.TITLE).let { if (it.isNullOrBlank()) updatedSong.title else it },
                    artist = tag.getFirst(FieldKey.ARTIST).let { if (it.isNullOrBlank()) updatedSong.artist else it },
                    album = tag.getFirst(FieldKey.ALBUM).let { if (it.isNullOrBlank() || it == "Unknown Album") updatedSong.album else it },
                    albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST).let { if (it.isNullOrBlank()) updatedSong.albumArtist else it },
                    genre = tag.getFirst(FieldKey.GENRE).let { if (it.isNullOrBlank()) updatedSong.genre else it },
                    year = tag.getFirst(FieldKey.YEAR)?.toIntOrNull() ?: updatedSong.year,
                    composer = tag.getFirst(FieldKey.COMPOSER).let { if (it.isNullOrBlank()) updatedSong.composer else it },
                    trackNumber = tag.getFirst(FieldKey.TRACK)?.toIntOrNull() ?: updatedSong.trackNumber,
                    discNumber = tag.getFirst(FieldKey.DISC_NO)?.toIntOrNull() ?: updatedSong.discNumber,
                    lyrics = tag.getFirst(FieldKey.LYRICS).ifBlank {
                        tag.getFirst(FieldKey.CUSTOM1).ifBlank {
                            tag.getFirst("LYRICS")
                        }
                    }.let { if (it.isNullOrBlank()) updatedSong.lyrics else it },
                    replayGainTrackDb = parseReplayGainDb(tag.getFirst("REPLAYGAIN_TRACK_GAIN")) ?: updatedSong.replayGainTrackDb,
                    replayGainAlbumDb = parseReplayGainDb(tag.getFirst("REPLAYGAIN_ALBUM_GAIN")) ?: updatedSong.replayGainAlbumDb,
                    replayGainTrackPeak = tag.getFirst("REPLAYGAIN_TRACK_PEAK")?.toFloatOrNull() ?: updatedSong.replayGainTrackPeak,
                    replayGainAlbumPeak = tag.getFirst("REPLAYGAIN_ALBUM_PEAK")?.toFloatOrNull() ?: updatedSong.replayGainAlbumPeak
                )

                if (fetchArt && artworkEnabled && updatedSong.albumArtUri == null) {
                    tag.firstArtwork?.binaryData?.let {
                        updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, it))
                    }
                }
            }

            if (header != null) {
                updatedSong = updatedSong.copy(
                    durationMs = if (updatedSong.durationMs <= 0) (header.trackLength * 1000).toLong() else updatedSong.durationMs,
                    bitrate = if (updatedSong.bitrate <= 0) header.bitRateAsNumber.toInt() * 1000 else updatedSong.bitrate,
                    sampleRateHz = if (updatedSong.sampleRateHz <= 0) header.sampleRateAsNumber else updatedSong.sampleRateHz
                )

                // ALAC detection for M4A/MP4 containers
                val formatUpper = updatedSong.format.uppercase()
                if (formatUpper == "M4A" || formatUpper == "MP4" || formatUpper == "AUDIO") {
                    val encoding = header.encodingType?.uppercase() ?: ""
                    if (encoding.contains("ALAC") || header.javaClass.simpleName.contains("Mp4", ignoreCase = true)) {
                        // If it's Mp4 and bitrate is high, it's likely ALAC
                        if (encoding.contains("ALAC") || updatedSong.bitrate > 500000) {
                            updatedSong = updatedSong.copy(format = "ALAC")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            updatedSong = extractMetadataWithFFprobe(updatedSong, localFile)
        }

        // Only mark as enriched if we actually got valid data to prevent caching "Unknown"
        val valid = updatedSong.durationMs > 0 && !updatedSong.artist.contains("Unknown", ignoreCase = true)

        // Feature: Online Genre Enrichment
        if (updatedSong.genre.isBlank() || updatedSong.genre.contains("Unknown", ignoreCase = true)) {
            onlineGenreService.fetchAccurateGenre(updatedSong.artist, updatedSong.title)?.let { onlineGenre ->
                updatedSong = updatedSong.copy(genre = onlineGenre)
            }
        }

        updatedSong.copy(
            isEnriched = valid,
            albumArtFetchAttempted = fetchArt && artworkEnabled,
            lastSyncTimestamp = System.currentTimeMillis()
        )
    }

    private fun cacheEmbeddedAlbumArt(songId: String, bytes: ByteArray): Uri? {
        val dir = File(context.filesDir, "album_art").apply { mkdirs() }
        val f = File(dir, "$songId.jpg")
        
        val prefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val useOriginalQuality = prefs.getBoolean("use_original_quality_art", false)

        return try {
            if (!useOriginalQuality && bytes.size > 100 * 1024) {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                
                var sampleSize = 1
                while (options.outWidth / (sampleSize * 2) >= 512 && options.outHeight / (sampleSize * 2) >= 512) {
                    sampleSize *= 2
                }
                
                val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                
                if (bitmap != null) {
                    FileOutputStream(f).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                    }
                    bitmap.recycle()
                    return Uri.fromFile(f)
                }
            }
            
            FileOutputStream(f).use { it.write(bytes) }
            Uri.fromFile(f)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun downloadPart(fileId: String, dest: File, credential: GoogleAccountCredential, range: String?, offset: Long) = withContext(Dispatchers.IO) {
        try {
            val token = credential.getToken() ?: return@withContext
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (range != null) {
                connection.setRequestProperty("Range", range)
            }
            connection.connect()

            if (connection.responseCode == 200 || connection.responseCode == 206) {
                var raf: RandomAccessFile? = null
                try {
                    raf = RandomAccessFile(dest, "rw")
                    raf.seek(offset)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var readCount: Int
                        while (input.read(buffer).also { readCount = it } != -1) {
                            raf.write(buffer, 0, readCount)
                        }
                    }
                } finally {
                    try { raf?.close() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadPart failed: ${e.message}")
        }
    }

    private fun extractMetadataWithFFprobe(song: Song, file: File): Song {
        val session = FFprobeKit.execute("-v quiet -print_format json -show_format -show_streams ${file.absolutePath}")
        if (ReturnCode.isSuccess(session.returnCode)) {
            val json = JSONObject(session.output ?: "{}")
            val formatJson = json.optJSONObject("format")
            val tags = formatJson?.optJSONObject("tags")
            val streams = json.optJSONArray("streams")
            val audioStream = (0 until (streams?.length() ?: 0))
                .mapNotNull { streams?.optJSONObject(it) }
                .firstOrNull { it.optString("codec_type") == "audio" }

            val bitrate = formatJson?.optString("bit_rate")?.toIntOrNull() ?: 0
            val sampleRate = audioStream?.optString("sample_rate")?.toIntOrNull() ?: 0
            val duration = (formatJson?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1000.0
            val codecName = audioStream?.optString("codec_name")?.lowercase()
            
            val isAlac = codecName == "alac"
            val isWav = codecName?.contains("pcm") == true || codecName?.contains("wav") == true

            fun getTag(vararg keys: String): String? {
                for (key in keys) {
                    val value = tags?.optString(key).takeIf { !it.isNullOrBlank() }
                    if (value != null) return value
                }
                return null
            }

            return song.copy(
                title = getTag("title", "TITLE", "INAM") ?: song.title,
                artist = getTag("artist", "ARTIST", "IART") ?: song.artist,
                album = getTag("album", "ALBUM", "IPRD") ?: song.album,
                genre = getTag("genre", "GENRE", "IGNR") ?: song.genre,
                year = getTag("date", "DATE", "ICRD", "YEAR", "year")?.take(4)?.toIntOrNull() ?: song.year,
                albumArtist = getTag("album_artist", "ALBUMARTIST") ?: song.albumArtist,
                trackNumber = getTag("track", "TRACK", "ITRK")?.substringBefore("/")?.toIntOrNull() ?: song.trackNumber,
                format = when {
                    isAlac -> "ALAC"
                    isWav -> "WAV"
                    else -> song.format
                },
                bitrate = if (song.bitrate <= 0) bitrate else song.bitrate,
                sampleRateHz = if (song.sampleRateHz <= 0) sampleRate else song.sampleRateHz,
                durationMs = if (song.durationMs <= 0) duration.toLong() else song.durationMs
            )
        }
        return song
    }

    private fun parseReplayGainDb(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        return Regex("""([+-]?\d+(?:\.\d+)?)""").find(value)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractWavMetadataManual(tempFile: File, song: Song): Song {
        var updatedSong = song
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(tempFile, "r")
            val fileLen = raf.length()
            if (fileLen < 12) return song

            // Skip leading junk if any (though rare for WAV)
            var foundRiff = false
            while (raf.filePointer + 12 <= fileLen && raf.filePointer < 4096) {
                if (readFourCc(raf) == "RIFF") {
                    foundRiff = true
                    break
                }
                raf.seek(raf.filePointer - 3)
            }
            if (!foundRiff) return song

            raf.skipBytes(4) // skip file size
            if (readFourCc(raf) != "WAVE") return song

            while (raf.filePointer + 8 <= fileLen) {
                val chunkId = readFourCc(raf)
                val chunkSize = readLittleEndianInt(raf).toLong().and(0xFFFFFFFFL)
                val chunkStart = raf.filePointer

                // Safety break for zero-filled holes in partial files
                if (chunkId.all { it == '\u0000' } && chunkSize == 0L) {
                    if (raf.filePointer > 1024_000 && raf.filePointer < fileLen - 1024_000) {
                        // We are likely in the undownloaded middle section
                        // Jump towards the end where footer might be
                        raf.seek(fileLen - 1024_000)
                        continue
                    }
                    break
                }

                when (chunkId.trim().uppercase()) {
                    "ID3" -> {
                        if (chunkSize > 0 && chunkSize <= (fileLen - chunkStart)) {
                            val bytes = ByteArray(chunkSize.toInt())
                            raf.readFully(bytes)
                            updatedSong = extractMetadataFromId3(bytes, updatedSong)
                        }
                    }
                    "LIST" -> {
                        if (chunkSize >= 4 && chunkSize <= (fileLen - chunkStart)) {
                            val listType = WavArtHelper.readFourCc(raf) // Using helper to read tag list
                            if (listType == "INFO") {
                                updatedSong = extractMetadataFromInfoList(raf, chunkStart + chunkSize, updatedSong)
                            }
                        }
                    }
                }

                raf.seek(chunkStart + chunkSize)
                if ((chunkSize % 2) != 0L && raf.filePointer < fileLen) raf.skipBytes(1)
            }
        } catch (e: Exception) {
            // ignore
        } finally {
            try { raf?.close() } catch (e: Exception) {}
        }
        return updatedSong
    }

    private fun extractMetadataFromInfoList(raf: RandomAccessFile, listEnd: Long, song: Song): Song {
        var updatedSong = song
        try {
            while (raf.filePointer + 8 <= listEnd) {
                val subChunkId = readFourCc(raf)
                val subChunkSize = readLittleEndianInt(raf).toLong().and(0xFFFFFFFFL)
                val subChunkStart = raf.filePointer

                if (subChunkSize > 0 && subChunkSize <= (listEnd - subChunkStart)) {
                    val bytes = ByteArray(subChunkSize.toInt())
                    raf.readFully(bytes)
                    val text = String(bytes).trimEnd { it == '\u0000' || it.isWhitespace() }

                    updatedSong = when (subChunkId.uppercase()) {
                        "INAM" -> updatedSong.copy(title = if (text.isNotBlank()) text else updatedSong.title)
                        "IART" -> updatedSong.copy(artist = if (text.isNotBlank()) text else updatedSong.artist)
                        "IPRD" -> updatedSong.copy(album = if (text.isNotBlank() && text != "Unknown Album") text else updatedSong.album)
                        "IGNR" -> updatedSong.copy(genre = if (text.isNotBlank()) text else updatedSong.genre)
                        "ICRD" -> updatedSong.copy(year = text.take(4).toIntOrNull() ?: updatedSong.year)
                        "ITRK" -> updatedSong.copy(trackNumber = text.substringBefore("/").toIntOrNull() ?: updatedSong.trackNumber)
                        else -> updatedSong
                    }
                }

                raf.seek(subChunkStart + subChunkSize)
                if ((subChunkSize % 2) != 0L && raf.filePointer < listEnd) raf.skipBytes(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WAV INFO list for ${song.title}", e)
        }
        return updatedSong
    }

    private fun extractMetadataFromId3(bytes: ByteArray, song: Song): Song {
        var updatedSong = song
        if (bytes.size < 10) return song
        if (bytes[0].toInt().toChar() != 'I' || bytes[1].toInt().toChar() != 'D' || bytes[2].toInt().toChar() != '3') return song

        val version = bytes[3].toInt()
        val totalSize = synchsafeToInt(bytes.sliceArray(6..9))
        var offset = 10

        while (offset + (if (version == 2) 6 else 10) <= bytes.size && offset < totalSize + 10) {
            val frameId: String
            val frameSize: Int
            val headerSize: Int

            if (version == 2) {
                frameId = String(bytes.sliceArray(offset..offset + 2))
                frameSize = ((bytes[offset + 3].toInt() and 0xFF) shl 16) or
                            ((bytes[offset + 4].toInt() and 0xFF) shl 8) or
                            (bytes[offset + 5].toInt() and 0xFF)
                headerSize = 6
            } else {
                frameId = String(bytes.sliceArray(offset..offset + 3))
                frameSize = if (version >= 4) synchsafeToInt(bytes.sliceArray(offset + 4..offset + 7))
                            else bytesToInt(bytes.sliceArray(offset + 4..offset + 7), 0)
                headerSize = 10
            }

            if (frameSize <= 0 || offset + headerSize + frameSize > bytes.size) break

            val frameData = bytes.sliceArray(offset + headerSize until offset + headerSize + frameSize)

            when (frameId) {
                "TIT2", "TT2" -> updatedSong = updatedSong.copy(title = parseId3TextFrame(frameData) ?: updatedSong.title)
                "TPE1", "TP1" -> updatedSong = updatedSong.copy(artist = parseId3TextFrame(frameData) ?: updatedSong.artist)
                "TALB", "TAL" -> {
                    val album = parseId3TextFrame(frameData)
                    if (album != null && album != "Unknown Album") updatedSong = updatedSong.copy(album = album)
                }
                "TCON", "TCO" -> updatedSong = updatedSong.copy(genre = parseId3TextFrame(frameData) ?: updatedSong.genre)
                "TYER", "TDRC", "TYE" -> {
                    val year = parseId3TextFrame(frameData)?.take(4)?.toIntOrNull()
                    if (year != null) updatedSong = updatedSong.copy(year = year)
                }
                "TRCK", "TRK" -> {
                    val track = parseId3TextFrame(frameData)?.substringBefore("/")?.toIntOrNull()
                    if (track != null) updatedSong = updatedSong.copy(trackNumber = track)
                }
            }
            offset += headerSize + frameSize
        }
        return updatedSong
    }

    private fun parseId3TextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val encoding = data[0].toInt()
        return try {
            val raw = data.copyOfRange(1, data.size)
            val text = when (encoding) {
                0 -> {
                    // Many taggers mislabel UTF-8 content as ISO-8859-1 (encoding byte = 0).
                    // Prefer UTF-8 when the bytes decode cleanly; fall back to ISO-8859-1 otherwise.
                    if (isValidUtf8(raw)) String(raw, Charsets.UTF_8) else String(raw, Charsets.ISO_8859_1)
                }
                1 -> String(raw, Charsets.UTF_16)
                2 -> String(raw, Charsets.UTF_16BE)
                3 -> String(raw, Charsets.UTF_8)
                else -> String(raw, Charsets.UTF_8)
            }
            text.trimEnd { it == '\u0000' || it.isWhitespace() }
        } catch (e: Exception) {
            null
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean =
        try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); true }
        catch (e: Exception) { false }

    private fun extractEmbeddedArtWithFfmpeg(songId: String, file: File): Uri? {
        val outputFile = File(File(context.filesDir, "album_art").apply { mkdirs() }, "$songId.jpg")
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y",
                "-v", "error",
                "-i", file.absolutePath,
                "-map", "0:v:0",
                "-frames:v", "1",
                outputFile.absolutePath
            )
        )
        return if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L) {
            Uri.fromFile(outputFile)
        } else {
            null
        }
    }

    private fun isAlacFile(file: File): Boolean {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var foundAlac = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.contains("alac", ignoreCase = true)) {
                    foundAlac = true
                    break
                }
            }
            foundAlac
        } catch (e: Exception) {
            // If extractor fails, check bitrate as fallback
            val path = file.absolutePath.lowercase()
            if (path.endsWith(".alac") || path.endsWith(".caf")) return true
            false
        } finally {
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    private fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return (bytes[0].toInt() and 0xFF) or
               ((bytes[1].toInt() and 0xFF) shl 8) or
               ((bytes[2].toInt() and 0xFF) shl 16) or
               ((bytes[3].toInt() and 0xFF) shl 24)
    }

    private fun synchsafeToInt(bytes: ByteArray): Int {
        if (bytes.size < 4) return 0
        return (bytes[0].toInt() and 0x7F shl 21) or (bytes[1].toInt() and 0x7F shl 14) or (bytes[2].toInt() and 0x7F shl 7) or (bytes[3].toInt() and 0x7F)
    }

    private fun bytesToInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    }
}
