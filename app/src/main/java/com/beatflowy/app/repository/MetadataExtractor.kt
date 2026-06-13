package com.beatflowy.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.model.SyncQuality
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MetadataExtractor(private val context: Context) {

    private val TAG = "MetadataExtractor"
    private val batchSemaphore = Semaphore(12) // Reverted to standard parallelism
    
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
        credential: GoogleAccountCredential,
        dataSaver: Boolean = false,
        artworkEnabled: Boolean = true,
        quality: SyncQuality = SyncQuality.MEDIUM
    ): Song = withContext(Dispatchers.IO) {
        if (song.source != SongSource.GDRIVE || song.driveFileId == null) return@withContext song
        
        // Feature 3: Skip if already enriched and accurate
        if (song.isEnriched && !dataSaver && song.durationMs > 0) {
             return@withContext song
        }

        // Speedup UNDONE: Every song now performs its own full extraction to ensure 100% accuracy
        // No more album-level metadata sharing which was causing incorrect art/tags
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

            var updatedSong = song
            
            // Re-enabled WAV Footer for end-of-file tags
            if (isWav && song.fileSizeBytes > headerSize) {
                val footerSize = 2048_000L // Increased to 2MB for large ID3 tags
                val start = (song.fileSizeBytes - footerSize).coerceAtLeast(0L)
                downloadPart(song.driveFileId, tempFile, credential, "bytes=$start-${song.fileSizeBytes - 1}", start)
            }

            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                if (fetchArt && artworkEnabled) {
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, artBytes))
                    }
                }
                
                if (updatedSong.albumArtUri == null && isWav && fetchArt && artworkEnabled) {
                    updatedSong = updatedSong.copy(albumArtUri = extractWavArtManual(tempFile, song.id))
                }

                if (updatedSong.albumArtUri == null && fetchArt && artworkEnabled && (isWav || updatedSong.format == "ALAC")) {
                    // FFmpeg fallback for complex containers or missing headers
                    val ffmpegArt = extractEmbeddedArtWithFfmpeg(song.id, tempFile)
                    if (ffmpegArt != null) {
                        updatedSong = updatedSong.copy(albumArtUri = ffmpegArt)
                    }
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
                val audioFile = AudioFileIO.read(tempFile)
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
                        lyrics = tag.getFirst(FieldKey.LYRICS).let { if (it.isNullOrBlank()) updatedSong.lyrics else it },
                        replayGainTrackDb = parseReplayGainDb(tag.getFirst("REPLAYGAIN_TRACK_GAIN")) ?: updatedSong.replayGainTrackDb,
                        replayGainAlbumDb = parseReplayGainDb(tag.getFirst("REPLAYGAIN_ALBUM_GAIN")) ?: updatedSong.replayGainAlbumDb
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
                    val format = updatedSong.format.uppercase()
                    if (format == "M4A" || format == "MP4" || format == "AUDIO") {
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
                updatedSong = extractMetadataWithFFprobe(updatedSong, tempFile)
            }

            // Only mark as enriched if we actually got valid data to prevent caching "Unknown"
            val valid = updatedSong.durationMs > 0 && !updatedSong.artist.contains("Unknown", ignoreCase = true)

            return@withContext updatedSong.copy(
                isEnriched = valid,
                lastSyncTimestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata for ${song.title}", e)
            return@withContext song
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun cacheEmbeddedAlbumArt(songId: String, bytes: ByteArray): Uri? {
        val dir = File(context.cacheDir, "embedded_album_art").apply { mkdirs() }
        val f = File(dir, "cloud_$songId.jpg")
        
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

    private fun downloadPart(fileId: String, dest: File, credential: GoogleAccountCredential, range: String, offset: Long) {
        try {
            val token = credential.token ?: return
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Range", range)
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

            return song.copy(
                title = tags?.optString("title") ?: tags?.optString("TITLE") ?: song.title,
                artist = tags?.optString("artist") ?: tags?.optString("ARTIST") ?: song.artist,
                album = tags?.optString("album") ?: tags?.optString("ALBUM") ?: song.album,
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

    private fun extractWavArtManual(tempFile: File, songId: String): Uri? {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(tempFile, "r")
            val fileLen = raf.length()
            if (fileLen < 12) return null
            if (readFourCc(raf) != "RIFF") return null
            raf.skipBytes(4)
            if (readFourCc(raf) != "WAVE") return null
            
            while (raf.filePointer + 8 <= fileLen) {
                val chunkId = readFourCc(raf)
                val chunkSize = readLittleEndianInt(raf).toLong().and(0xFFFFFFFFL)
                
                if (chunkId == "ID3 " || chunkId == "id3 ") {
                    if (chunkSize > 0 && chunkSize <= (fileLen - raf.filePointer)) {
                        val bytes = ByteArray(chunkSize.toInt())
                        raf.readFully(bytes)
                        extractApicFromId3(bytes)?.let { art ->
                            return cacheEmbeddedAlbumArt(songId, art)
                        }
                    } else break
                } else if (chunkId == "DISP") {
                    if (chunkSize > 4 && chunkSize <= (fileLen - raf.filePointer)) {
                        raf.skipBytes(4)
                        val bytes = ByteArray((chunkSize - 4).toInt())
                        raf.readFully(bytes)
                        if (bytes.isNotEmpty()) {
                            return cacheEmbeddedAlbumArt(songId, bytes)
                        }
                    } else break
                } else if (chunkId == "LIST") {
                    // Handle LIST INFO or LIST adtl chunks that might contain ID3
                    if (chunkSize >= 4 && chunkSize <= (fileLen - raf.filePointer)) {
                        val listType = readFourCc(raf)
                        if (listType == "INFO" || listType == "adtl" || listType == "ID3 ") {
                             // Deep scan within LIST if it's small enough, otherwise skip
                             if (chunkSize < 1024_000) {
                                 // We just skip for now and let FFmpeg handle it if it's complex
                                 raf.seek(raf.filePointer + chunkSize - 4)
                             } else {
                                 raf.seek(raf.filePointer + chunkSize - 4)
                             }
                        } else {
                            raf.seek(raf.filePointer + chunkSize - 4)
                        }
                    } else break
                } else {
                    if (chunkSize >= 0 && chunkSize <= (fileLen - raf.filePointer)) {
                        raf.seek(raf.filePointer + chunkSize)
                    } else break
                }
                if ((chunkSize % 2) != 0L && raf.filePointer < fileLen) raf.skipBytes(1)
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            try { raf?.close() } catch (e: Exception) {}
        }
    }

    private fun extractEmbeddedArtWithFfmpeg(songId: String, file: File): Uri? {
        val outputFile = File(File(context.cacheDir, "embedded_album_art").apply { mkdirs() }, "cloud_ffmpeg_$songId.jpg")
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

    private fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun extractApicFromId3(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10 || String(bytes, 0, 3) != "ID3") return null
        val tagSize = synchsafeToInt(bytes.copyOfRange(6, 10))
        var offset = 10
        while (offset + 10 <= bytes.size && offset < 10 + tagSize) {
            val frameId = String(bytes, offset, 4)
            val frameSize = bytesToInt(bytes, offset + 4)
            if (frameSize <= 0 || offset + 10 + frameSize > bytes.size) break
            if (frameId == "APIC") {
                return parseApicFrame(bytes.copyOfRange(offset + 10, offset + 10 + frameSize))
            }
            offset += 10 + frameSize
        }
        return null
    }

    private fun parseApicFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xFF
        var index = 1
        while (index < frame.size && frame[index].toInt() != 0) index++
        index++
        if (index >= frame.size) return null
        index++ // picture type
        if (encoding == 0 || encoding == 3) {
            while (index < frame.size && frame[index].toInt() != 0) index++
            index++
        } else {
            while (index + 1 < frame.size && !(frame[index].toInt() == 0 && frame[index + 1].toInt() == 0)) index += 2
            index += 2
        }
        return if (index in 0 until frame.size) frame.copyOfRange(index, frame.size) else null
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
