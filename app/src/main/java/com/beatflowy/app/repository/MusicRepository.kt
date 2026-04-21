package com.beatflowy.app.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.beatflowy.app.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream

class MusicRepository(private val context: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scanAudioFiles(
        fullScan: Boolean = true,
        onProgress: (count: Int, albumCount: Int, artistCount: Int, progress: Float) -> Unit
    ): List<Song> = withContext(Dispatchers.IO) {
        val albumsSet = mutableSetOf<String>()
        val artistsSet = mutableSetOf<String>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.YEAR
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.BITRATE)
            }
        }.toTypedArray()

        val selection  = "${MediaStore.Audio.Media.DURATION} > 5000"
        val sortOrder  = "${MediaStore.Audio.Media.TITLE} ASC"

        val rawList = mutableListOf<RawSongData>()

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
            val idCol      = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val yearCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            } else -1

            while (c.moveToNext()) {
                rawList.add(RawSongData(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol) ?: "Unknown Title",
                    artist = c.getString(artistCol) ?: "Unknown Artist",
                    album = c.getString(albumCol) ?: "Unknown Album",
                    duration = c.getLong(durCol),
                    mime = c.getString(mimeCol) ?: "",
                    size = c.getLong(sizeCol),
                    albumId = c.getLong(albumIdCol),
                    bitrate = if (bitrateCol != -1) c.getInt(bitrateCol) else 0,
                    path = c.getString(dataCol) ?: "",
                    dateAdded = c.getLong(dateCol),
                    year = c.getInt(yearCol)
                ))
            }
        }

        if (rawList.isEmpty()) return@withContext emptyList<Song>()

        val total = rawList.size
        var processedCount = 0
        val processedSongs = mutableListOf<Song>()
        
        if (!fullScan) {
            // Quick scan: just use MediaStore data without expensive MediaMetadataRetriever
            rawList.forEach { raw ->
                val uri = ContentUris.withAppendedId(collection, raw.id)
                processedSongs.add(Song(
                    id = raw.id.toString(),
                    uri = uri,
                    title = raw.title,
                    artist = raw.artist,
                    album = raw.album,
                    durationMs = raw.duration,
                    format = mimeToFormat(raw.mime, raw.path, 16),
                    sampleRateHz = guessSampleRate(raw.mime),
                    bitDepth = guessBitDepth(raw.mime),
                    bitrate = raw.bitrate,
                    fileSizeBytes = raw.size,
                    albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), raw.albumId),
                    year = raw.year,
                    dateAdded = raw.dateAdded,
                    folder = raw.path.substringBeforeLast("/", "Unknown")
                ))
                albumsSet.add(raw.album)
                artistsSet.add(raw.artist)
            }
            onProgress(total, albumsSet.size, artistsSet.size, 1.0f)
            return@withContext processedSongs.sortedBy { it.title }
        }

        // Use flatMapMerge for high-concurrency parallel processing.
        rawList.asFlow()
            .flatMapMerge(concurrency = 8) { raw ->
                flow {
                    val uri = ContentUris.withAppendedId(collection, raw.id)
                    var sampleRate = 44100
                    var bitDepth = 16
                    var formatName = "MP3"
                    var genre = "Unknown"
                    val fallbackAlbumArt = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        raw.albumId
                    )
                    var albumArtUri: Uri = fallbackAlbumArt

                    val retriever = MediaMetadataRetriever()
                    val extractor = MediaExtractor()
                    try {
                        retriever.setDataSource(context, uri)
                        extractor.setDataSource(context, uri, null)
                        
                        // Prefer lossless / primary audio when MP4/M4A exposes multiple tracks
                        var trackFormat: android.media.MediaFormat? = null
                        var bestAudioPriority = -1
                        for (i in 0 until extractor.trackCount) {
                            val f = extractor.getTrackFormat(i)
                            val m = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                            if (!m.startsWith("audio/")) continue
                            val p = when {
                                m.contains("alac", true) -> 120
                                m.contains("flac", true) -> 110
                                m.contains("opus", true) -> 105
                                m.contains("vorbis", true) -> 100
                                m.contains("mpeg", true) || m.contains("mp3", true) -> 95
                                m.contains("mp4a", true) || m.contains("aac", true) || m.contains("latm", true) -> 90
                                else -> 10
                            }
                            if (p > bestAudioPriority) {
                                bestAudioPriority = p
                                trackFormat = f
                            }
                        }

                        val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        val br = brStr?.toIntOrNull() ?: 0

                        if (trackFormat != null) {
                            if (trackFormat.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = trackFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            }
                            // Try to get bit depth from extractor
                            if (trackFormat.containsKey("bits-per-sample")) {
                                bitDepth = trackFormat.getInteger("bits-per-sample")
                            } else if (trackFormat.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                                val encoding = trackFormat.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                                bitDepth = when (encoding) {
                                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                    android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                                    else -> 16
                                }
                            }
                        }
                        
                        // Fallback to retriever for bit depth on API 31+
                        if (bitDepth <= 16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val bdStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                            if (!bdStr.isNullOrEmpty()) bitDepth = bdStr.toInt()
                        }

                        // Heuristic for high-res lossless if still unknown
                        if (bitDepth <= 16 && br > 2116000) bitDepth = 24

                        val extractorMime = trackFormat?.getString(android.media.MediaFormat.KEY_MIME)?.lowercase() ?: ""
                        val extension = raw.path.substringAfterLast(".", "").lowercase()

                        formatName = when {
                            extractorMime.contains("flac") || extension == "flac" -> "FLAC"
                            extractorMime.contains("wav") || extractorMime.contains("x-raw") || extension == "wav" -> "WAV"
                            extractorMime.contains("alac") || extension == "alac" -> "ALAC"
                            extractorMime.contains("mp4a") || extractorMime.contains("aac") || extension == "m4a" || extension == "aac" -> {
                                if (br > 400000 || bitDepth > 16 || raw.size > 8_000_000 || extractorMime.contains("alac")) "ALAC" else "AAC"
                            }
                            extractorMime.contains("mpeg") || extension == "mp3" -> "MP3"
                            extractorMime.contains("ogg") || extension == "ogg" -> "OGG"
                            extractorMime.contains("opus") || extension == "opus" -> "OPUS"
                            else -> "MP3"
                        }

                        genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "Unknown"

                        val artBytes = runCatching { retriever.embeddedPicture }.getOrNull()
                        if (artBytes != null && artBytes.isNotEmpty()) {
                            albumArtUri = cacheEmbeddedAlbumArt(raw.id, raw.albumId, artBytes)
                        }
                    } catch (e: Exception) {
                        formatName = mimeToFormat(raw.mime, raw.path, bitDepth)
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                        try { extractor.release() } catch (e: Exception) {}
                    }

                    emit(Song(
                        id = raw.id.toString(),
                        uri = uri,
                        title = raw.title,
                        artist = raw.artist,
                        album = raw.album,
                        durationMs = raw.duration,
                        format = formatName,
                        sampleRateHz = sampleRate,
                        bitDepth = bitDepth,
                        bitrate = raw.bitrate,
                        fileSizeBytes = raw.size,
                        albumArtUri = albumArtUri,
                        year = raw.year,
                        genre = genre,
                        dateAdded = raw.dateAdded,
                        folder = raw.path.substringBeforeLast("/", "Unknown")
                    ))
                }
            }
            .collect { song ->
                processedSongs.add(song)
                albumsSet.add(song.album)
                artistsSet.add(song.artist)
                processedCount++
                
                if (processedCount % 10 == 0 || processedCount == total) {
                    onProgress(processedCount, albumsSet.size, artistsSet.size, processedCount.toFloat() / total)
                }
            }

        processedSongs.sortedBy { it.title }
    }

    private fun guessSampleRate(mime: String): Int = when {
        mime.contains("flac", true) || mime.contains("wav", true) -> 48000
        else -> 44100
    }

    private fun guessBitDepth(mime: String): Int = when {
        mime.contains("flac", true) || mime.contains("wav", true) -> 16
        else -> 16
    }

    private fun cacheEmbeddedAlbumArt(mediaStoreId: Long, albumId: Long, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "embedded_album_art").apply { mkdirs() }
        val f = File(dir, "$mediaStoreId.jpg")
        return try {
            FileOutputStream(f).use { it.write(bytes) }
            Uri.fromFile(f)
        } catch (_: Exception) {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        }
    }

    private fun mimeToFormat(mime: String, path: String, bitDepth: Int): String {
        val ext = path.substringAfterLast(".", "").lowercase()
        val m = mime.lowercase()
        return when {
            ext == "flac" || m.contains("flac") -> "FLAC"
            ext == "wav" || m.contains("wav") || m.contains("wave") -> "WAV"
            ext == "alac" || m.contains("alac") -> "ALAC"
            ext == "m4a" || ext == "aac" || m.contains("mp4") || m.contains("aac") -> {
                // If it's ALAC, it's usually inside M4A container
                if (bitDepth > 16 || m.contains("alac")) "ALAC" else "AAC"
            }
            ext == "ogg" || m.contains("ogg") -> "OGG"
            ext == "opus" || m.contains("opus") -> "OPUS"
            ext == "mp3" || m.contains("mpeg") -> "MP3"
            else -> "MP3"
        }
    }

    private data class RawSongData(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val mime: String,
        val size: Long,
        val albumId: Long,
        val bitrate: Int,
        val path: String,
        val dateAdded: Long,
        val year: Int
    )
}
