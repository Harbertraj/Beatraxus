package com.beatflowy.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
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
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject

class MetadataExtractor(private val context: Context) {

    private val TAG = "MetadataExtractor"
    private val batchSemaphore = Semaphore(12)

    suspend fun extractCloudMetadataBatch(
        songs: List<Song>,
        credential: GoogleAccountCredential,
        onProgress: (suspend (Song) -> Unit)? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        songs.map { song ->
            async {
                batchSemaphore.withPermit {
                    val updated = extractCloudMetadata(song, credential)
                    onProgress?.invoke(updated)
                    updated
                }
            }
        }.awaitAll()
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

    suspend fun extractCloudMetadata(song: Song, credential: GoogleAccountCredential): Song = withContext(Dispatchers.IO) {
        if (song.source != SongSource.GDRIVE || song.driveFileId == null) return@withContext song

        val format = song.format.lowercase()
        val isWav = format.contains("wav")
        val extension = if (format == "audio") "" else ".$format"
        val tempFile = File(context.cacheDir, "metadata_temp_${song.id}$extension")
        
        try {
            // 1. Download header (8MB)
            downloadPart(song.driveFileId, tempFile, credential, "bytes=0-8388607", 0L)

            var updatedSong = song
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                val artBytes = retriever.embeddedPicture
                if (artBytes != null && artBytes.isNotEmpty()) {
                    updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, artBytes))
                } else if (isWav) {
                    updatedSong = updatedSong.copy(albumArtUri = extractWavArtManual(tempFile, song.id))
                    
                    // 2. Fallback: Download footer if no art found in header (many WAVs have tags at end)
                    if (updatedSong.albumArtUri == null && song.fileSizeBytes > 8388608L) {
                        val footerSize = 512_000L
                        val start = (song.fileSizeBytes - footerSize).coerceAtLeast(0L)
                        downloadPart(song.driveFileId, tempFile, credential, "bytes=$start-${song.fileSizeBytes - 1}", start)
                        updatedSong = updatedSong.copy(albumArtUri = extractWavArtManual(tempFile, song.id))
                    }
                }
                
                val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val genre = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                val yearStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)

                updatedSong = updatedSong.copy(
                    title = title ?: updatedSong.title,
                    artist = artist ?: updatedSong.artist,
                    album = if (album != null && album != "Unknown Album") album else updatedSong.album,
                    genre = genre ?: updatedSong.genre,
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
                    val albumTag = tag.getFirst(FieldKey.ALBUM)
                    val titleTag = tag.getFirst(FieldKey.TITLE)
                    val artistTag = tag.getFirst(FieldKey.ARTIST)
                    val albumArtistTag = tag.getFirst(FieldKey.ALBUM_ARTIST)
                    val genreTag = tag.getFirst(FieldKey.GENRE)
                    val yearTag = tag.getFirst(FieldKey.YEAR)
                    val composerTag = tag.getFirst(FieldKey.COMPOSER)
                    val trackTag = tag.getFirst(FieldKey.TRACK)
                    val discTag = tag.getFirst(FieldKey.DISC_NO)
                    val lyricsTag = tag.getFirst(FieldKey.LYRICS)

                    updatedSong = updatedSong.copy(
                        title = if (titleTag.isNullOrBlank()) updatedSong.title else titleTag as String,
                        artist = if (artistTag.isNullOrBlank()) updatedSong.artist else artistTag as String,
                        album = if (albumTag.isNullOrBlank() || albumTag == "Unknown Album") updatedSong.album else albumTag as String,
                        albumArtist = if (albumArtistTag.isNullOrBlank()) updatedSong.albumArtist else albumArtistTag as String,
                        genre = if (genreTag.isNullOrBlank()) updatedSong.genre else genreTag as String,
                        year = yearTag?.toIntOrNull() ?: updatedSong.year,
                        composer = if (composerTag.isNullOrBlank()) updatedSong.composer else composerTag as String,
                        trackNumber = trackTag?.toIntOrNull() ?: updatedSong.trackNumber,
                        discNumber = discTag?.toIntOrNull() ?: updatedSong.discNumber,
                        lyrics = if (lyricsTag.isNullOrBlank()) updatedSong.lyrics else lyricsTag as String,
                        replayGainTrackDb = parseReplayGainDb(tag.getFirst("REPLAYGAIN_TRACK_GAIN")) ?: updatedSong.replayGainTrackDb,
                        replayGainAlbumDb = parseReplayGainDb(tag.getFirst("REPLAYGAIN_ALBUM_GAIN")) ?: updatedSong.replayGainAlbumDb,
                        replayGainTrackPeak = tag.getFirst("REPLAYGAIN_TRACK_PEAK")?.toFloatOrNull() ?: updatedSong.replayGainTrackPeak,
                        replayGainAlbumPeak = tag.getFirst("REPLAYGAIN_ALBUM_PEAK")?.toFloatOrNull() ?: updatedSong.replayGainAlbumPeak
                    )

                    val artwork = tag.firstArtwork
                    if (artwork != null && updatedSong.albumArtUri == null) {
                        updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, artwork.binaryData))
                    }
                }

                if (header != null) {
                    val encoding = header.encodingType ?: ""
                    val isAlac = encoding.lowercase().indexOf("alac") != -1 || encoding.lowercase().indexOf("apple lossless") != -1
                    updatedSong = updatedSong.copy(
                        format = if (isAlac) "ALAC" else updatedSong.format,
                        durationMs = if (updatedSong.durationMs <= 0) (header.trackLength * 1000).toLong() else updatedSong.durationMs,
                        bitrate = if (updatedSong.bitrate <= 0) header.bitRateAsNumber.toInt() * 1000 else updatedSong.bitrate,
                        sampleRateHz = if (updatedSong.sampleRateHz <= 0) header.sampleRateAsNumber else updatedSong.sampleRateHz,
                        bitDepth = if (updatedSong.bitDepth <= 0) guessBitDepth(encoding, header.bitRateAsNumber.toInt(), header.sampleRateAsNumber) else updatedSong.bitDepth
                    )
                }
            } catch (e: Exception) {
                if (isWav && updatedSong.albumArtUri == null) {
                    updatedSong = updatedSong.copy(albumArtUri = extractWavArtManual(tempFile, song.id))
                }
                updatedSong = extractMetadataWithFFprobe(updatedSong, tempFile)
            }

            return@withContext updatedSong
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata for ${song.title}", e)
            return@withContext song
        } finally {
            if (tempFile.exists()) tempFile.delete()
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
            val codecName = audioStream?.optString("codec_name")?.lowercase() ?: ""

            val album = tags?.optString("album") ?: tags?.optString("ALBUM") ?: song.album

            var updatedSong = song.copy(
                title = tags?.optString("title") ?: tags?.optString("TITLE") ?: song.title,
                artist = tags?.optString("artist") ?: tags?.optString("ARTIST") ?: song.artist,
                album = if (album == "Unknown Album") song.album else album,
                format = if (codecName.indexOf("alac") != -1) "ALAC" else song.format,
                bitrate = if (song.bitrate <= 0) bitrate else song.bitrate,
                sampleRateHz = if (song.sampleRateHz <= 0) sampleRate else song.sampleRateHz,
                durationMs = if (song.durationMs <= 0) duration.toLong() else song.durationMs
            )

            if (updatedSong.albumArtUri == null) {
                val ffmpegArtFile = File(context.cacheDir, "art_ffmpeg_${song.id}.jpg")
                val extractArtSession = com.arthenica.ffmpegkit.FFmpegKit.execute("-i ${file.absolutePath} -an -vcodec copy -frames:v 1 -y ${ffmpegArtFile.absolutePath}")
                if (ReturnCode.isSuccess(extractArtSession.returnCode) && ffmpegArtFile.exists() && ffmpegArtFile.length() > 128L) {
                    updatedSong = updatedSong.copy(albumArtUri = cacheEmbeddedAlbumArt(song.id, ffmpegArtFile.readBytes()))
                    ffmpegArtFile.delete()
                }
            }
            return updatedSong
        }
        return song
    }

    private fun parseReplayGainDb(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        return Regex("""([+-]?\d+(?:\.\d+)?)""").find(value)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun guessBitDepth(codec: String?, bitrateKbps: Int, sampleRate: Int): Int {
        if (codec == null) return 16
        val c = codec.lowercase()
        if (c.indexOf("flac") != -1 || c.indexOf("wav") != -1 || c.indexOf("pcm") != -1 || c.indexOf("alac") != -1 || c.indexOf("apple lossless") != -1) {
            return if (bitrateKbps >= 2116 || sampleRate > 48000) 24 else 16
        }
        return 0
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
                    if (chunkSize > 0 && chunkSize < (fileLen - raf.filePointer)) {
                        val bytes = ByteArray(chunkSize.toInt())
                        raf.readFully(bytes)
                        extractApicFromId3(bytes)?.let { art ->
                            return cacheEmbeddedAlbumArt(songId, art)
                        }
                    } else break
                } else if (chunkId == "DISP") {
                    if (chunkSize > 4 && chunkSize < (fileLen - raf.filePointer)) {
                        raf.skipBytes(4)
                        val bytes = ByteArray((chunkSize - 4).toInt())
                        raf.readFully(bytes)
                        if (bytes.isNotEmpty()) {
                            return cacheEmbeddedAlbumArt(songId, bytes)
                        }
                    } else break
                } else if (chunkId == "data") {
                    if (chunkSize > (fileLen - raf.filePointer)) {
                        if (fileLen > 8388608L) {
                            val footerStart = fileLen - 512000L
                            if (raf.filePointer < footerStart) {
                                raf.seek(footerStart)
                                continue
                            } else {
                                break
                            }
                        } else break
                    } else {
                        raf.seek(raf.filePointer + chunkSize)
                    }
                } else {
                    if (chunkSize > 0 && chunkSize < (fileLen - raf.filePointer)) {
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
