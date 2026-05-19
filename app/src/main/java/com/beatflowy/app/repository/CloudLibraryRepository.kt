package com.beatflowy.app.repository

import android.net.Uri
import com.beatflowy.app.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class CloudLibraryRepository(
    private val allSongs: Flow<List<Song>>,
    private val selectedEmail: Flow<String?>
) : LibraryRepository {

    override fun getSongs(): Flow<List<Song>> = combine(allSongs, selectedEmail) { songs, email ->
        songs.filter { it.source == com.beatflowy.app.model.SongSource.GDRIVE && (email == null || it.driveAccountEmail == email) }
    }

    override fun getAlbums(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }

    override fun getArtists(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        songs.groupBy { it.artist }
            .map { (name, list) -> Triple(name, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }

    override fun getYears(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        songs.groupBy { it.year }
            .map { (year, list) -> Triple(year.toString(), "${list.size} songs", list.first().albumArtUri) }
            .sortedByDescending { it.first }
    }

    override fun getGenres(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        songs.groupBy { it.genre }
            .map { (genre, list) -> Triple(genre, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }
}
