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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max

class MusicRepository(private val context: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scanAudioFiles(
        fullScan: Boolean = true,
        targetPath: String? = null,
        excludedPaths: List<String> = emptyList(),
        onProgress: (count: Int, albumCount: Int, artistCount: Int, progress: Float) -> Unit
    ): List<Song> = withContext(Dispatchers.IO) {
        onProgress(0, 0, 0, 0f)
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

        val selection = StringBuilder("${MediaStore.Audio.Media.DURATION} > 5000")
        val selectionArgs = mutableListOf<String>()

        if (targetPath != null) {
            val resolvedPath = if (targetPath.startsWith("content://")) {
                resolveUriToPath(targetPath)
            } else {
                targetPath
            }
            
            if (resolvedPath != null) {
                val normalizedTarget = if (resolvedPath.endsWith("/")) resolvedPath else "$resolvedPath/"
                selection.append(" AND ${MediaStore.Audio.Media.DATA} LIKE ?")
                selectionArgs.add("$normalizedTarget%")
            }
        }

        excludedPaths.forEach { path ->
            val normalizedExcluded = if (path.endsWith("/")) path else "$path/"
            selection.append(" AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?")
            selectionArgs.add("$normalizedExcluded%")
        }

        val sortOrder  = "${MediaStore.Audio.Media.TITLE} ASC"

        val rawById = linkedMapOf<Long, RawSongData>()

        context.contentResolver.query(collection, projection, selection.toString(), if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(), sortOrder)?.use { c ->
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
        
        val concurrency = 30 // Increased to 30 parallel workers for maximum speed as requested

        rawList.asFlow()
            .flatMapMerge(concurrency = concurrency) { raw ->
                flow {
                    val uri = ContentUris.withAppendedId(collection, raw.id)
                    val extension = raw.path.substringAfterLast(".", "").lowercase()
                    
                    // Force extraction for containers that can be both lossy and lossless
                    val isLosslessCandidate = extension == "flac" || extension == "wav" || extension == "alac" || extension == "m4a" || extension == "caf" ||
                                     raw.mime.contains("flac") || raw.mime.contains("wav") || raw.mime.contains("alac") ||
                                     raw.mime.contains("dsd") || raw.mime.contains("aiff")

                    // QUICK AND ACCURATE: 
                    // Use retriever for lossless candidates or when full scan is requested.
                    // This ensures accuracy for high-res files while keeping MP3/AAC scans fast.
                    val shouldReadRetriever = fullScan || isLosslessCandidate || raw.bitrate <= 0 || 
                                            raw.genre.isBlank() || raw.genre.equals("unknown", ignoreCase = true)
                    
                    var sampleRate = guessSampleRate(raw.mime, raw.path)
                    var bitDepth = guessBitDepth(raw.mime, raw.path, raw.size, raw.duration)
                    var formatName = mimeToFormat(raw.mime, raw.path, bitDepth)
                    var genre = raw.genre.ifEmpty { "Unknown" }
                    val fallbackAlbumArt = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        raw.albumId
                    )
                    var albumArtUri: Uri = fallbackAlbumArt
                    var replayGain = ReplayGainMetadata()
                    var albumArtist: String? = null
                    var trackNumber: Int? = null
                    var discNumber: Int? = null
                    var composer: String? = null
                    var lyrics: String? = null

                    if (shouldReadRetriever) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            
                            albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
                            discNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.toIntOrNull()
                            composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                            // METADATA_KEY_LYRIC = 23
                            lyrics = retriever.extractMetadata(23)

                            if (genre == "Unknown") {
                                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "Unknown"
                            }
                            val br = if (raw.bitrate > 0) raw.bitrate else {
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                            }

                            if (fullScan || isLosslessCandidate) {
                                val artBytes = runCatching { retriever.embeddedPicture }.getOrNull()
                                if (artBytes != null && artBytes.isNotEmpty()) {
                                    albumArtUri = cacheEmbeddedAlbumArt(raw.id, raw.albumId, artBytes, forceRefresh = fullScan)
                                } else if (extension == "wav") {
                                    // Special handling for WAV files which often fail with MediaMetadataRetriever
                                    val wavArt = extractEmbeddedArtFromWavFile(raw.path, raw.id, raw.albumId)
                                    if (wavArt != null) albumArtUri = wavArt
                                }
                                
                                // Last resort fallback: FFmpeg if art is still the default and it's a deep scan or lossless
                                if (albumArtUri == fallbackAlbumArt && (fullScan || isLosslessCandidate)) {
                                    val ffmpegArt = extractEmbeddedArtWithFfmpeg(raw.id, uri)
                                    if (ffmpegArt != null) albumArtUri = ffmpegArt
                                }

                                // ONLY extract ReplayGain if it's a FULL scan. FFprobe is too slow for quick scan.
                                if (fullScan) {
                                    replayGain = extractReplayGain(uri)
                                }

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
                                        
                                        // Precise identification for M4A container (ALAC vs AAC)
                                        val durationMin = raw.duration / 60000.0
                                        val sizeMb = raw.size / (1024.0 * 1024.0)
                                        val mbPerMin = if (durationMin > 0) sizeMb / durationMin else 0.0
                                        
                                        val isActuallyLossyM4A = (extension == "m4a" || extension == "mp4") && 
                                            (mbPerMin < 2.1 || (br > 0 && br < 400000))

                                        val isActuallyAlac = extractorMime.contains("alac") || 
                                                           (!isActuallyLossyM4A && (mbPerMin >= 2.1 || br >= 400000) && (extension == "m4a" || extension == "alac"))

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
                                        
                                        if (formatName == "AAC" || formatName == "MP3" || formatName == "OPUS" || formatName == "OGG") {
                                            bitDepth = 0
                                        }
                                    }
                                } finally {
                                    try { extractor.release() } catch (e: Exception) {}
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
                        folder = raw.path.substringBeforeLast("/", "Unknown"),
                        replayGainTrackDb = replayGain.trackGainDb,
                        replayGainAlbumDb = replayGain.albumGainDb,
                        replayGainTrackPeak = replayGain.trackPeak,
                        replayGainAlbumPeak = replayGain.albumPeak,
                        albumArtist = albumArtist,
                        trackNumber = trackNumber,
                        discNumber = discNumber,
                        composer = composer,
                        lyrics = lyrics,
                        source = SongSource.LOCAL
                    ))
                }
            }
            .collect { song ->
                processedSongs.add(song)
                albumsSet.add(song.album)
                artistsSet.add(song.artist)
                processedCount++
                
                // Report more frequently at the start to feel more immediate
                val reportFrequency = when {
                    processedCount <= 10 -> 1
                    processedCount <= 50 -> 5
                    else -> 20
                }
                
                if (processedCount % reportFrequency == 0 || processedCount == total) {
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

    private fun guessBitDepth(mime: String, path: String, size: Long = 0, duration: Long = 0): Int {
        val m = mime.lowercase()
        val p = path.lowercase()
        return when {
            m.contains("flac") || m.contains("wav") || m.contains("alac") ||
                p.endsWith(".flac") || p.endsWith(".wav") || p.endsWith(".alac") ||
                p.endsWith(".aiff") || p.endsWith(".aif") -> 16
            p.endsWith(".m4a") || m.contains("mp4") -> {
                // Heuristic for ALAC in M4A container (typically > 2.5MB/min)
                if (duration > 0 && size > 0) {
                    val mbPerMin = (size / (1024.0 * 1024.0)) / (duration / 60000.0)
                    if (mbPerMin > 2.3) 16 else 0 
                } else 0
            }
            else -> 0
        }
    }

    private fun cacheEmbeddedAlbumArt(mediaStoreId: Long, albumId: Long, bytes: ByteArray, forceRefresh: Boolean = false): Uri {
        val dir = File(context.filesDir, "embedded_album_art").apply { mkdirs() }
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

    private fun extractEmbeddedArtWithFfmpeg(mediaStoreId: Long, uri: Uri): Uri? {
        val outputFile = File(File(context.filesDir, "embedded_album_art").apply { mkdirs() }, "$mediaStoreId-ffmpeg.jpg")
        val inputSource = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y",
                "-v", "error",
                "-i", inputSource,
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

    private fun extractEmbeddedArtFromWavFile(path: String, mediaStoreId: Long, albumId: Long): Uri? {
        if (path.isBlank()) return null
        return runCatching {
            val art = WavArtHelper.extractArt(path)
            if (art != null) {
                cacheEmbeddedAlbumArt(mediaStoreId, albumId, art, forceRefresh = true)
            } else {
                null
            }
        }.getOrElse {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        }
    }

    private fun extractReplayGain(uri: Uri): ReplayGainMetadata {
        return runCatching {
            val source = FFmpegKitConfig.getSafParameterForRead(context, uri)
            val session = FFprobeKit.executeWithArguments(
                arrayOf("-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", source)
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                return@runCatching ReplayGainMetadata()
            }

            val json = JSONObject(session.output.orEmpty())
            val formatTags = json.optJSONObject("format")?.optJSONObject("tags")
            val streams = json.optJSONArray("streams")
            val streamTags = buildList {
                if (streams != null) {
                    for (index in 0 until streams.length()) {
                        streams.optJSONObject(index)?.optJSONObject("tags")?.let(::add)
                    }
                }
            }

            val tagValue: (String) -> String? = { key ->
                formatTags?.optString(key)?.takeIf { it.isNotBlank() }
                    ?: streamTags.firstNotNullOfOrNull { tags ->
                        tags.optString(key).takeIf { it.isNotBlank() }
                    }
                    ?: formatTags?.keys()?.asSequence()
                        ?.firstOrNull { it.equals(key, ignoreCase = true) }
                        ?.let { formatTags.optString(it) }
                    ?: streamTags.firstNotNullOfOrNull { tags ->
                        tags.keys().asSequence()
                            .firstOrNull { it.equals(key, ignoreCase = true) }
                            ?.let { tags.optString(it) }
                            ?.takeIf { it.isNotBlank() }
                    }
            }

            ReplayGainMetadata(
                trackGainDb = parseReplayGainDb(tagValue("REPLAYGAIN_TRACK_GAIN")),
                albumGainDb = parseReplayGainDb(tagValue("REPLAYGAIN_ALBUM_GAIN")),
                trackPeak = parsePeak(tagValue("REPLAYGAIN_TRACK_PEAK")),
                albumPeak = parsePeak(tagValue("REPLAYGAIN_ALBUM_PEAK"))
            )
        }.getOrDefault(ReplayGainMetadata())
    }

    private fun parseReplayGainDb(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        return Regex("""([+-]?\d+(?:\.\d+)?)""").find(value)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun parsePeak(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        return value.trim().toFloatOrNull()
    }

    private data class ReplayGainMetadata(
        val trackGainDb: Float? = null,
        val albumGainDb: Float? = null,
        val trackPeak: Float? = null,
        val albumPeak: Float? = null
    )

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

    fun normalizePath(path: String): String {
        return try {
            val file = File(path)
            file.canonicalPath
        } catch (e: Exception) {
            path.trimEnd('/')
        }
    }

    private fun resolveUriToPath(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if ("com.android.externalstorage.documents" == uri.authority) {
            val docId = try {
                // For tree URIs, the document ID is in the last segment
                uri.pathSegments.last()
            } catch (e: Exception) {
                return null
            }
            val split = docId.split(":")
            val type = split[0]
            if ("primary".equals(type, ignoreCase = true)) {
                return "/storage/emulated/0/" + if (split.size > 1) split[1] else ""
            } else {
                // Non-primary storage (SD cards)
                return "/storage/$type/" + if (split.size > 1) split[1] else ""
            }
        }
        return null
    }

    // Deprecated: Moving to Room-based folder management
    fun getMusicFolders(): List<String> {
        val prefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val foldersJson = prefs.getString("music_folders", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(foldersJson)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addMusicFolder(uri: String) {
        val current = getMusicFolders().toMutableList()
        if (!current.contains(uri)) {
            current.add(uri)
            saveMusicFolders(current)
        }
    }

    fun addMusicFolders(uris: List<String>) {
        val current = getMusicFolders().toMutableSet()
        var changed = false
        uris.forEach { uri ->
            if (current.add(uri)) {
                changed = true
            }
        }
        if (changed) {
            saveMusicFolders(current.toList())
        }
    }

    fun removeMusicFolder(uri: String) {
        val current = getMusicFolders().toMutableList()
        if (current.remove(uri)) {
            saveMusicFolders(current)
            addBlockedFolder(uri)
        }
    }

    private fun saveMusicFolders(folders: List<String>) {
        val prefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        folders.forEach { array.put(it) }
        prefs.edit().putString("music_folders", array.toString()).apply()
    }

    fun getBlockedFolders(): List<String> {
        val prefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val foldersJson = prefs.getString("blocked_folders", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(foldersJson)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addBlockedFolder(uri: String) {
        val current = getBlockedFolders().toMutableSet()
        if (current.add(uri)) {
            saveBlockedFolders(current.toList())
        }
    }

    fun removeBlockedFolder(uri: String) {
        val current = getBlockedFolders().toMutableList()
        if (current.remove(uri)) {
            saveBlockedFolders(current)
        }
    }

    private fun saveBlockedFolders(folders: List<String>) {
        val prefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        folders.forEach { array.put(it) }
        prefs.edit().putString("blocked_folders", array.toString()).apply()
    }
}
