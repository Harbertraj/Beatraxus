package com.beatraxus.app.repository

import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.AiAnalysisDao
import com.beatraxus.app.util.ArtistNameUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class LocalLibraryRepository(
    private val allSongs: Flow<List<Song>>,
    private val favorites: Flow<Set<String>>,
    private val aiAnalysisDao: AiAnalysisDao
) : LibraryRepository {

    private val allAnalysis = aiAnalysisDao.getAllAnalysisFlow()

    override fun getSongs(): Flow<List<Song>> = allSongs.map { songs ->
        songs.filter { it.source == com.beatraxus.app.model.SongSource.LOCAL }
    }

    override fun getMoods(): Flow<List<Triple<String, String, Uri?>>> = 
        combine(getSongs(), allAnalysis) { songs, analysis ->
            val analysisMap = analysis.associateBy { it.songId }
            songs.filter { analysisMap.containsKey(it.id) }
                .groupBy { analysisMap[it.id]?.mood ?: "Unknown" }
                .map { (mood, list) -> Triple(mood, "${list.size} songs", list.first().albumArtUri) }
                .sortedBy { it.first }
        }

    override fun getLanguages(): Flow<List<Triple<String, String, Uri?>>> = 
        combine(getSongs(), allAnalysis) { songs, analysis ->
            val analysisMap = analysis.associateBy { it.songId }
            songs.filter { analysisMap.containsKey(it.id) }
                .groupBy { analysisMap[it.id]?.language ?: "Unknown" }
                .map { (lang, list) -> Triple(lang, "${list.size} songs", list.first().albumArtUri) }
                .sortedBy { it.first }
        }

    override fun getAlbums(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }

    override fun getArtists(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        // Each song contributes to every artist it credits
        val exploded = songs.flatMap { song ->
            ArtistNameUtils.splitArtists(song.artist).map { artistName -> artistName to song }
        }

        exploded
            .groupBy { (name, _) -> ArtistNameUtils.normalizeKey(name) }
            .map { (_, pairs) ->
                // pick the most common display-name spelling as canonical
                val displayName = pairs.map { it.first }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }!!.key
                val uniqueSongs = pairs.map { it.second }.distinctBy { it.id }
                Triple(displayName, "${uniqueSongs.size} songs", uniqueSongs.first().albumArtUri)
            }
            .sortedBy { it.first.lowercase() }
    }

    override fun getYears(): Flow<List<Triple<String, String, Uri?>>> = getSongs().map { songs ->
        songs.groupBy { it.year }
            .map { (year, list) -> Triple(year.toString(), "${list.size} songs", list.first().albumArtUri) }
            .sortedByDescending { it.first }
    }

    override fun getGenres(): Flow<List<Triple<String, String, Uri?>>> = 
        combine(getSongs(), allAnalysis) { songs, analysis ->
            val analysisMap = analysis.associateBy { it.songId }
            songs.groupBy { analysisMap[it.id]?.genre ?: it.genre }
                .map { (genre, list) -> Triple(genre, "${list.size} songs", list.first().albumArtUri) }
                .sortedBy { it.first.lowercase() }
        }
}
