package com.beatflowy.app.repository

import com.beatflowy.app.model.Song
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun getSongs(): Flow<List<Song>>
    fun getAlbums(): Flow<List<Triple<String, String, android.net.Uri?>>>
    fun getArtists(): Flow<List<Triple<String, String, android.net.Uri?>>>
    fun getYears(): Flow<List<Triple<String, String, android.net.Uri?>>>
    fun getGenres(): Flow<List<Triple<String, String, android.net.Uri?>>>
}
