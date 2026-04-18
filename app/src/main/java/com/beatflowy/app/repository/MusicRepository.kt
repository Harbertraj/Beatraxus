package com.beatflowy.app.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.beatflowy.app.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MusicRepository(private val context: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scanAudioFiles(
        fullScan: Boolean = true,
        onProgress: (count: Int, albumCount: Int, artistCount: Int, progress: Float) -> Unit
    ): List<Song> = withContext(Dispatchers.IO) {
        val albums = mutableSetOf<String>()
        val artists = mutableSetOf<String>()
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
                    format = mimeToFormat(raw.mime, 16),
                    sampleRateHz = guessSampleRate(raw.mime),
                    bitDepth = guessBitDepth(raw.mime),
                    bitrate = raw.bitrate,
                    fileSizeBytes = raw.size,
                    albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), raw.albumId),
                    year = raw.year,
                    dateAdded = raw.dateAdded,
                    folder = raw.path.substringBeforeLast("/", "Unknown")
                ))
                albums.add(raw.album)
                artists.add(raw.artist)
            }
            onProgress(total, albums.size, artists.size, 1.0f)
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

                    // ALAC/AAC/M4A specific detection logic
                    val isM4A = raw.mime.contains("video/mp4", true) || 
                               raw.mime.contains("audio/mp4", true) || 
                               raw.mime.contains("audio/x-m4a", true) ||
                               raw.title.endsWith(".m4a", true)

                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        
                        // Extract sample rate and bit depth
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val sr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            val bd = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                            if (!sr.isNullOrEmpty()) sampleRate = sr.toInt()
                            if (!bd.isNullOrEmpty()) bitDepth = bd.toInt()
                        }

                        // Detailed format detection
                        val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        val br = brStr?.toIntOrNull() ?: 0

                        formatName = when {
                            raw.mime.contains("flac", true) -> "FLAC"
                            raw.mime.contains("wav", true) || raw.mime.contains("wave", true) -> "WAV"
                            isM4A -> {
                                // 16-bit ALAC is usually > 500kbps, while AAC is <= 512kbps (Apple's max).
                                // But bitDepth > 16 is a definitive ALAC marker.
                                if (br > 512_000 || bitDepth > 16) "ALAC" else "AAC"
                            }
                            raw.mime.contains("ogg", true) -> "OGG"
                            raw.mime.contains("opus", true) -> "OPUS"
                            else -> "MP3"
                        }
                        
                        // Fallback for ALAC if MediaMetadataRetriever didn't catch bitDepth
                        if (formatName == "ALAC" && bitDepth <= 16) {
                            if (br > 1000000) bitDepth = 24
                        } else if (formatName == "MP3") {
                            // MP3 is always 16-bit at most for PCM conversion, but some receivers report 32.
                            bitDepth = 16
                        }

                    } catch (e: Exception) {
                        // Fallback to basic mime mapping
                        formatName = mimeToFormat(raw.mime, bitDepth)
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
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
                        albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), raw.albumId),
                        year = raw.year,
                        dateAdded = raw.dateAdded,
                        folder = raw.path.substringBeforeLast("/", "Unknown")
                    ))
                }
            }
            .collect { song ->
                processedSongs.add(song)
                albums.add(song.album)
                artists.add(song.artist)
                processedCount++
                
                // Batch progress updates to avoid flooding the UI thread
                if (processedCount % 10 == 0 || processedCount == total) {
                    onProgress(processedCount, albums.size, artists.size, processedCount.toFloat() / total)
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

    private fun mimeToFormat(mime: String, bitDepth: Int): String = when {
        mime.contains("flac", true) -> "FLAC"
        mime.contains("wav", true) -> "WAV"
        mime.contains("alac", true) || (mime.contains("m4a", true) && bitDepth > 16) -> "ALAC"
        mime.contains("m4a", true) || mime.contains("aac", true) -> "AAC"
        mime.contains("ogg", true) -> "OGG"
        mime.contains("opus", true) -> "OPUS"
        else -> "MP3"
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
