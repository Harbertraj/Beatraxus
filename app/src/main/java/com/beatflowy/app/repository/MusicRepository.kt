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
import java.util.Locale
import kotlin.math.max

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
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        val selection  = "${MediaStore.Audio.Media.DURATION} > 5000"
        val sortOrder  = "${MediaStore.Audio.Media.TITLE} ASC"

        val rawById = linkedMapOf<Long, RawSongData>()

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
            val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1
            val genreCol   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1

            while (c.moveToNext()) {
                val rawSong = RawSongData(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol) ?: "Unknown Title",
                    artist = c.getString(artistCol) ?: "Unknown Artist",
                    album = c.getString(albumCol) ?: "Unknown Album",
                    duration = c.getLong(durCol),
                    mime = c.getString(mimeCol) ?: "",
                    size = c.getLong(sizeCol),
                    albumId = c.getLong(albumIdCol),
                    bitrate = if (bitrateCol != -1) c.getInt(bitrateCol) else 0,
                    genre = if (genreCol != -1) c.getString(genreCol) ?: "" else "",
                    path = c.getString(dataCol) ?: "",
                    dateAdded = c.getLong(dateCol),
                    year = c.getInt(yearCol)
                )
                rawById[rawSong.id] = rawSong
            }
        }

        val rawList = rawById.values.toList()
        if (rawList.isEmpty()) return@withContext emptyList<Song>()

        val total = rawList.size
        var processedCount = 0
        val processedSongs = mutableListOf<Song>()
        
        // Optimize: If not a full scan, we do NOT use MediaMetadataRetriever/MediaExtractor at all.
        // This makes the first scan 10x-20x faster.
        if (!fullScan) {
            rawList.forEach { raw ->
                val uri = ContentUris.withAppendedId(collection, raw.id)
                processedSongs.add(Song(
                    id = raw.id.toString(),
                    uri = uri,
                    title = raw.title,
                    artist = raw.artist,
                    album = raw.album,
                    durationMs = raw.duration,
                    format = mimeToFormat(raw.mime, raw.path, guessBitDepth(raw.mime, raw.path)),
                    sampleRateHz = guessSampleRate(raw.mime, raw.path),
                    bitDepth = guessBitDepth(raw.mime, raw.path),
                    bitrate = raw.bitrate,
                    fileSizeBytes = raw.size,
                    albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), raw.albumId),
                    year = raw.year,
                    genre = raw.genre.ifEmpty { "Unknown" },
                    dateAdded = raw.dateAdded,
                    folder = raw.path.substringBeforeLast("/", "Unknown")
                ))
                albumsSet.add(raw.album)
                artistsSet.add(raw.artist)
                
                processedCount++
                if (processedCount % 50 == 0 || processedCount == total) {
                    onProgress(processedCount, albumsSet.size, artistsSet.size, processedCount.toFloat() / total)
                }
            }
            return@withContext processedSongs.sortedBy { it.title }
        }

        val concurrency = max(2, minOf(6, Runtime.getRuntime().availableProcessors()))

        rawList.asFlow()
            .flatMapMerge(concurrency = concurrency) { raw ->
                flow {
                    val uri = ContentUris.withAppendedId(collection, raw.id)
                    var sampleRate = guessSampleRate(raw.mime, raw.path)
                    var bitDepth = guessBitDepth(raw.mime, raw.path)
                    var formatName = mimeToFormat(raw.mime, raw.path, bitDepth)
                    var genre = raw.genre.ifEmpty { "Unknown" }
                    val fallbackAlbumArt = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        raw.albumId
                    )
                    var albumArtUri: Uri = fallbackAlbumArt

                    val extension = raw.path.substringAfterLast(".", "").lowercase()
                    // Force extraction for containers that can be both lossy and lossless
                    val forceDeepScan = extension == "flac" || extension == "wav" || extension == "alac" || extension == "m4a" || extension == "caf" ||
                                     raw.mime.contains("flac") || raw.mime.contains("wav") || raw.mime.contains("alac") ||
                                     raw.mime.contains("dsd") || raw.mime.contains("aiff")

                    val shouldReadRetriever = fullScan && shouldReadWithRetriever(raw, forceDeepScan)
                    val shouldReadExtractor = fullScan && forceDeepScan

                    if (shouldReadRetriever) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            
                            if (genre == "Unknown") {
                                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "Unknown"
                            }
                            val br = if (raw.bitrate > 0) raw.bitrate else {
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                            }

                            val artBytes = runCatching { retriever.embeddedPicture }.getOrNull()
                            if (artBytes != null && artBytes.isNotEmpty()) {
                                albumArtUri = cacheEmbeddedAlbumArt(raw.id, raw.albumId, artBytes, forceRefresh = fullScan)
                            }

                            if (shouldReadExtractor) {
                                val extractor = MediaExtractor()
                                try {
                                    extractor.setDataSource(context, uri, null)
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

                                    if (trackFormat != null) {
                                        if (trackFormat.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                                            sampleRate = trackFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                                        }
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
                                        
                                        val extractorMime = trackFormat.getString(android.media.MediaFormat.KEY_MIME)?.lowercase() ?: ""
                                        val avgBitrate = if (raw.duration > 0) (raw.size * 8000) / raw.duration else 0L
                                        
                                        // Precise identification for M4A container (ALAC vs AAC)
                                        // 450kbps and 4.5MB/min thresholds for AAC
                                        val durationMin = raw.duration / 60000.0
                                        val sizeMb = raw.size / (1024.0 * 1024.0)
                                        val isActuallyLossyM4A = (extension == "m4a" || extension == "mp4") && 
                                            ((durationMin > 0 && (sizeMb / durationMin) < 4.5) || (br > 0 && br < 450000))

                                        val isActuallyAlac = extractorMime.contains("alac") || 
                                                           (!isActuallyLossyM4A && (avgBitrate > 500000 || br > 500000) && (extension == "m4a" || extension == "alac"))

                                        formatName = when {
                                            extractorMime.contains("flac") || extension == "flac" -> "FLAC"
                                            extractorMime.contains("wav") || extractorMime.contains("x-raw") || extension == "wav" -> "WAV"
                                            extractorMime.contains("alac") || extension == "alac" || extension == "caf" -> "ALAC"
                                            extractorMime.contains("mp4a") || extractorMime.contains("aac") || extension == "m4a" || extension == "aac" -> {
                                                if (isActuallyAlac) "ALAC" else "AAC"
                                            }
                                            extractorMime.contains("dsd") || extension == "dsf" || extension == "dff" -> "DSD"
                                            extractorMime.contains("aiff") || extension == "aiff" || extension == "aif" -> "AIFF"
                                            extractorMime.contains("mpeg") || extension == "mp3" -> "MP3"
                                            extractorMime.contains("ogg") || extension == "ogg" -> "OGG"
                                            extractorMime.contains("opus") || extension == "opus" -> "OPUS"
                                            else -> "MP3"
                                        }
                                        
                                        // If AAC or MP3, bit depth is irrelevant for "Lossless" labeling
                                        if (formatName == "AAC" || formatName == "MP3" || formatName == "OPUS" || formatName == "OGG") {
                                            bitDepth = 0
                                        }
                                    }
                                } finally {
                                    try { extractor.release() } catch (e: Exception) {}
                                }
                                
                                val artBytesRetriever = runCatching { retriever.embeddedPicture }.getOrNull()
                                if (artBytesRetriever != null && artBytesRetriever.isNotEmpty()) {
                                    albumArtUri = cacheEmbeddedAlbumArt(raw.id, raw.albumId, artBytesRetriever)
                                }
                            }

                            if (bitDepth <= 16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bitDepth > 0) {
                                val bdStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                                if (!bdStr.isNullOrEmpty()) bitDepth = bdStr.toInt()
                            }
                        } catch (e: Exception) {
                            formatName = mimeToFormat(raw.mime, raw.path, bitDepth)
                        } finally {
                            try { retriever.release() } catch (e: Exception) {}
                        }
                    }

                    // Final heuristics: High bitrate + non-lossy format means high quality lossy, but not "Lossless"
                    if (bitDepth <= 16 && raw.bitrate > 2116000 && bitDepth > 0) bitDepth = 24

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
                        bitrate = if (raw.bitrate > 0) raw.bitrate else 0,
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
                
                if (processedCount % 20 == 0 || processedCount == total) {
                    onProgress(processedCount, albumsSet.size, artistsSet.size, processedCount.toFloat() / total)
                }
            }

        processedSongs.sortedBy { it.title }
    }

    private fun shouldReadWithRetriever(raw: RawSongData, forceDeepScan: Boolean): Boolean {
        if (forceDeepScan) return true
        if (raw.bitrate <= 0) return true
        if (raw.genre.isBlank() || raw.genre.equals("unknown", ignoreCase = true)) return true
        return raw.albumId <= 0L
    }

    private fun guessSampleRate(mime: String, path: String): Int = when {
        mime.contains("flac", true) || mime.contains("wav", true) || mime.contains("alac", true) ||
            path.endsWith(".flac", true) || path.endsWith(".wav", true) || path.endsWith(".alac", true) ||
            path.endsWith(".aiff", true) || path.endsWith(".aif", true) -> 48000
        else -> 44100
    }

    private fun guessBitDepth(mime: String, path: String): Int = when {
        mime.contains("flac", true) || mime.contains("wav", true) || mime.contains("alac", true) ||
            path.endsWith(".flac", true) || path.endsWith(".wav", true) || path.endsWith(".alac", true) ||
            path.endsWith(".aiff", true) || path.endsWith(".aif", true) -> 16
        else -> 0 // 0 for lossy formats
    }

    private fun cacheEmbeddedAlbumArt(mediaStoreId: Long, albumId: Long, bytes: ByteArray, forceRefresh: Boolean = false): Uri {
        val dir = File(context.cacheDir, "embedded_album_art").apply { mkdirs() }
        val f = File(dir, "$mediaStoreId.jpg")
        
        if (!forceRefresh && f.exists() && f.length() > 0) return Uri.fromFile(f)

        val prefs = context.getSharedPreferences("beatraxus", android.content.Context.MODE_PRIVATE)
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
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        }
    }

    private fun mimeToFormat(mime: String, path: String, bitDepth: Int): String {
        val ext = path.substringAfterLast(".", "").lowercase()
        val m = mime.lowercase()
        return when {
            ext == "flac" || m.contains("flac") -> "FLAC"
            ext == "wav" || m.contains("wav") || m.contains("wave") -> "WAV"
            ext == "alac" || ext == "caf" || m.contains("alac") -> "ALAC"
            ext == "dsf" || ext == "dff" || m.contains("dsd") -> "DSD"
            ext == "aiff" || ext == "aif" || m.contains("aiff") -> "AIFF"
            ext == "m4a" || ext == "aac" || m.contains("mp4") || m.contains("aac") -> {
                if (m.contains("alac") || ext == "alac") "ALAC" else "AAC"
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
        val genre: String,
        val path: String,
        val dateAdded: Long,
        val year: Int
    )

    fun deleteSongs(uris: List<Uri>): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createDeleteRequest(context.contentResolver, uris)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                uris.forEach { context.contentResolver.delete(it, null, null) }
            } catch (e: SecurityException) {
                val recoverableSecurityException = e as? android.app.RecoverableSecurityException
                return recoverableSecurityException?.userAction?.actionIntent
            }
        } else {
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
        return null
    }
}
