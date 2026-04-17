package com.beatflowy.app.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.beatflowy.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    suspend fun scanAudioFiles(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection  = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val sortOrder  = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
            val idCol      = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (c.moveToNext()) {
                val id      = c.getLong(idCol)
                val mime    = c.getString(mimeCol) ?: ""
                val uri     = ContentUris.withAppendedId(collection, id)
                val albumId = c.getLong(albumIdCol)
                val artUri  = Uri.parse("content://media/external/audio/albumart/$albumId")

                songs.add(Song(
                    id           = id.toString(),
                    uri          = uri,
                    title        = c.getString(titleCol) ?: "Unknown Title",
                    artist       = c.getString(artistCol) ?: "Unknown Artist",
                    album        = c.getString(albumCol) ?: "Unknown Album",
                    durationMs   = c.getLong(durCol),
                    format       = mimeToFormat(mime),
                    sampleRateHz = guessSampleRate(mime),
                    bitDepth     = guessBitDepth(mime),
                    fileSizeBytes= c.getLong(sizeCol),
                    albumArtUri  = artUri
                ))
            }
        }
        songs
    }

    private fun mimeToFormat(mime: String) = when {
        mime.contains("flac", true) -> "FLAC"
        mime.contains("wav",  true) -> "WAV"
        mime.contains("aac",  true) -> "AAC"
        mime.contains("ogg",  true) -> "OGG"
        mime.contains("opus", true) -> "OPUS"
        else                        -> "MP3"
    }

    private fun guessSampleRate(mime: String) = when {
        mime.contains("flac", true) -> 96_000
        mime.contains("wav",  true) -> 48_000
        else                        -> 44_100
    }

    private fun guessBitDepth(mime: String) = when {
        mime.contains("flac", true) -> 24
        mime.contains("wav",  true) -> 24
        else                        -> 16
    }
}
