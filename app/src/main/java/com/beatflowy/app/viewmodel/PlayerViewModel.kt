package com.beatflowy.app.viewmodel

import java.io.File

import android.app.Application
import android.net.Uri
import android.view.Choreographer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.beatflowy.app.BeatraxusApplication
import com.beatflowy.app.engine.OutputMode
import com.beatflowy.app.model.PlaylistEntity
import com.beatflowy.app.model.FavoriteEntity
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.Playlist
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SortType
import com.beatflowy.app.model.ViewMode
import com.beatflowy.app.repository.MusicRepository
import com.beatflowy.app.repository.AutoEqRepository
import com.beatflowy.app.repository.LyricsRepository
import com.beatflowy.app.repository.LrcParser
import com.beatflowy.app.repository.LyricsSource
import com.beatflowy.app.service.AudioPlaybackService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val autoEqRepository = AutoEqRepository(application)
    private val lyricsRepository = LyricsRepository(application, (application as BeatraxusApplication).database)

    private val database = (application as BeatraxusApplication).database
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val songDao = database.songDao()

    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(PlayerUiState(
        isFirstRun = prefs.getBoolean("first_run", true),
        useOriginalQualityArt = prefs.getBoolean("use_original_quality_art", false),
        outputMode = OutputMode.fromName(prefs.getString(KEY_OUTPUT_MODE, null)).name
    ))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    private val _deleteRequest = MutableStateFlow<android.app.PendingIntent?>(null)
    val deleteRequest: StateFlow<android.app.PendingIntent?> = _deleteRequest.asStateFlow()

    private var pendingDeleteIds = emptyList<String>()
    private var libraryLoadJob: Job? = null
    private var serviceObserversJob: Job? = null

    fun consumeDeleteRequest() {
        _deleteRequest.value = null
    }

    fun onDeleteSuccess() {
        val ids = pendingDeleteIds
        if (ids.isEmpty()) return
        
        viewModelScope.launch {
            songDao.deleteSongsByIds(ids)
            _songs.update { currentSongs ->
                currentSongs.filterNot { it.id in ids }
            }
            pendingDeleteIds = emptyList()
            setMultiSelectMode(false)
            
            // If the current song was deleted, skip it
            if (ids.contains(_uiState.value.currentSong?.id)) {
                skipToNext()
            }
            // Remove from queue
            ids.forEach { id -> service?.removeFromQueue(id) }
        }
    }

    fun setUseOriginalQualityArt(enabled: Boolean) {
        viewModelScope.launch {
            // 1. Update preferences and state synchronously for the next scan
            withContext(Dispatchers.IO) {
                prefs.edit().putBoolean("use_original_quality_art", enabled).commit()
            }
            _uiState.update { it.copy(useOriginalQualityArt = enabled) }
            
            // 2. Clear cached album art so it can be re-extracted with the new quality setting
            withContext(Dispatchers.IO) {
                try {
                    val cacheDir = File(getApplication<android.app.Application>().cacheDir, "embedded_album_art")
                    if (cacheDir.exists()) {
                        cacheDir.deleteRecursively()
                    }
                } catch (e: Exception) {}
            }
            
            // 3. Force a full scan to re-cache images with new quality setting
            startFullScan()
        }
    }

    fun loadLibrary() {
        if (libraryLoadJob?.isActive == true) return
        _uiState.update { it.copy(permissionDenied = false) }
        libraryLoadJob = viewModelScope.launch {
            try {
                val dbSongs = withContext(Dispatchers.IO) {
                    songDao.getAllSongs().map { entity ->
                        Song(
                            id = entity.id,
                            uri = Uri.parse(entity.uriString),
                            title = entity.title,
                            artist = entity.artist,
                            album = entity.album,
                            durationMs = entity.durationMs,
                            format = entity.format,
                            sampleRateHz = entity.sampleRateHz,
                            bitDepth = entity.bitDepth,
                            bitrate = entity.bitrate,
                            fileSizeBytes = entity.fileSizeBytes,
                            albumArtUri = entity.albumArtUriString?.let { Uri.parse(it) },
                            year = entity.year,
                            genre = entity.genre,
                            folder = entity.folder,
                            dateAdded = entity.dateAdded
                        )
                    }
                }
                if (dbSongs.isNotEmpty()) {
                    // Check if cached album art still exists. If not, we need a refresh.
                    val cacheWiped = dbSongs.any { song ->
                        val artUri = song.albumArtUri
                        artUri != null && artUri.scheme == "file" && !File(artUri.path ?: "").exists()
                    }
                    
                    _songs.value = dbSongs
                    
                    if (cacheWiped) {
                        startFullScan()
                        return@launch
                    }
                }
            } catch (e: Exception) {
                // Ignore initial load errors
            }

            // If it's the first run, perform a high-concurrency Full Scan to populate UI correctly.
            // Otherwise, just a quick scan is enough to find new files.
            if (_uiState.value.isFirstRun) {
                startFullScan()
            } else {
                quickScan()
            }
        }
    }

    val playlists: StateFlow<List<Playlist>> = playlistDao.getAllPlaylists()
        .map { entities ->
            entities.map { entity ->
                Playlist(
                    id = entity.id,
                    name = entity.name,
                    songIds = entity.songIds.split(",").filter { it.isNotBlank() }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<Set<String>> = favoriteDao.getAllFavorites()
        .map { it.map { f -> f.songId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = combine(_songs, favorites) { songs, favoriteIds ->
        songs.map { it.copy(isFavorite = favoriteIds.contains(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums = allSongs.map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists = allSongs.map { songs ->
        songs.groupBy { it.artist }
            .map { (name, list) -> Triple(name, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders = combine(allSongs, _uiState) { songs, state ->
        val parentPath = state.currentFolderPath
        if (parentPath == null) {
            songs.groupBy { it.folder }
                .map { (path, list) -> Triple(path, path.substringAfterLast("/"), list.first().albumArtUri) }
                .sortedBy { it.second.lowercase() }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val years = allSongs.map { songs ->
        songs.groupBy { it.year }
            .map { (year, list) -> Triple(year.toString(), "${list.size} songs", list.first().albumArtUri) }
            .sortedByDescending { it.first }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genres = allSongs.map { songs ->
        songs.groupBy { it.genre }
            .map { (genre, list) -> Triple(genre, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val debouncedSearchQuery: StateFlow<String> = _uiState
        .map { it.searchQuery }
        .debounce(280)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val searchResults = combine(allSongs, debouncedSearchQuery) { all, query ->
        if (query.isEmpty()) return@combine emptyList<Any>()
        val list = mutableListOf<Any>()
        
        val matchedSongs = all.filter { it.title.contains(query, ignoreCase = true) }
        if (matchedSongs.isNotEmpty()) {
            list.add("Songs")
            list.addAll(matchedSongs.take(20))
        }
        
        val matchedAlbums = all.filter { it.album.contains(query, ignoreCase = true) }
            .distinctBy { it.album }
        if (matchedAlbums.isNotEmpty()) {
            list.add("Albums")
            matchedAlbums.take(10).forEach { 
                list.add(Triple(it.album, it.artist, it.albumArtUri)) 
            }
        }
        
        val matchedArtists = all.filter { it.artist.contains(query, ignoreCase = true) }
            .distinctBy { it.artist }
        if (matchedArtists.isNotEmpty()) {
            list.add("Artists")
            matchedArtists.take(10).forEach {
                list.add(Pair(it.artist, it.albumArtUri))
            }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recentlyPlayed = MutableStateFlow<List<String>>(emptyList())

    val songs: StateFlow<List<Song>> = combine(allSongs, _uiState, debouncedSearchQuery, _recentlyPlayed, playlists) { all, state, debouncedQuery, recentIds, pls ->
        var filtered = when (state.currentView) {
            LibraryView.ALL_SONGS -> all
            LibraryView.ALBUMS -> emptyList()
            LibraryView.ARTISTS -> emptyList()
            LibraryView.FOLDERS -> emptyList()
            LibraryView.YEARS -> emptyList()
            LibraryView.GENRES -> emptyList()
            LibraryView.FAVORITES -> all.filter { it.isFavorite }
            LibraryView.RECENTLY_ADDED -> all.sortedByDescending { it.dateAdded }
            LibraryView.RECENTLY_PLAYED -> {
                recentIds.filter { it != state.currentSong?.id }
                    .mapNotNull { id -> all.find { it.id == id } }
            }
            LibraryView.ALBUM_DETAIL -> all.filter { it.album == state.selectedItemName }
            LibraryView.ARTIST_DETAIL -> all.filter { it.artist == state.selectedItemName }
            LibraryView.FOLDER_DETAIL -> all.filter { it.folder == state.currentFolderPath }
            LibraryView.YEAR_DETAIL -> all.filter { it.year.toString() == state.selectedItemName }
            LibraryView.GENRE_DETAIL -> all.filter { it.genre == state.selectedItemName }
            LibraryView.PLAYLISTS -> emptyList()
            LibraryView.PLAYLIST_DETAIL -> {
                val playlist = pls.find { it.name == state.selectedItemName }
                playlist?.songIds?.mapNotNull { id -> all.find { it.id == id } } ?: emptyList()
            }
        }
        
        if (debouncedQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(debouncedQuery, ignoreCase = true) ||
                    it.artist.contains(debouncedQuery, ignoreCase = true) ||
                    it.album.contains(debouncedQuery, ignoreCase = true)
            }
        }

        val comparator = when (state.sortType) {
            com.beatflowy.app.model.SortType.NAME -> compareBy<Song> { it.title.lowercase() }
            com.beatflowy.app.model.SortType.DATE_ADDED -> compareBy { it.dateAdded }
            com.beatflowy.app.model.SortType.FILE_SIZE -> compareBy { it.fileSizeBytes }
            com.beatflowy.app.model.SortType.DURATION -> compareBy { it.durationMs }
        }

        if (state.isAscending) filtered.sortedWith(comparator)
        else filtered.sortedWith(comparator).reversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var service: AudioPlaybackService? = null

    fun attachService(svc: AudioPlaybackService) {
        if (service === svc) return
        service = svc
        svc.updateDspConfig(_uiState.value.dsp.config)
        svc.setOutputMode(OutputMode.fromName(_uiState.value.outputMode))
        serviceObserversJob?.cancel()
        serviceObserversJob = viewModelScope.launch {
            launch {
                svc.audioStateFlow.collect { audioState ->
                        _uiState.update {
                            it.copy(
                                inputSampleRate = audioState.sampleRate,
                                outputSampleRate = audioState.outputSampleRate,
                                bitDepth = if (audioState.bitDepth > 0) audioState.bitDepth else it.currentSong?.bitDepth ?: 16,
                                bitrate = if (audioState.bitrate > 0) audioState.bitrate else it.currentSong?.bitrate ?: 0,
                                format = audioState.codec.ifBlank { it.currentSong?.format ?: "" },
                                outputDevice = audioState.outputDevice,
                                pipelineOutputPath = audioState.outputPath,
                                pipelineDvcEnabled = audioState.dynamicVolumeControlActive,
                                pipelineResamplerEnabled = audioState.resamplerActive,
                                pipelineActiveEffects = audioState.activeEffects,
                                autoEqProfileName = audioState.autoEqProfileName
                            )
                        }
                    }
            }
            launch {
                svc.playbackStateFlow.collect { pbState ->
                        val prevSongId = _uiState.value.currentSong?.id
                        val nextSongId = pbState.currentSong?.id
                        val resetProgress = nextSongId == null || nextSongId != prevSongId

                        _uiState.update {
                            val sameSong = it.currentSong?.id == pbState.currentSong?.id
                            it.copy(
                                isPlaying = pbState.isPlaying,
                                currentSong = pbState.currentSong,
                                shuffleMode = pbState.shuffleMode,
                                repeatMode = pbState.repeatMode.ordinal,
                                bitrate = if (sameSong && it.bitrate > 0) it.bitrate else pbState.currentSong?.bitrate ?: 0,
                                format = if (sameSong && it.format.isNotBlank()) it.format else pbState.currentSong?.format ?: ""
                            )
                        }

                        if (resetProgress) {
                            _progressMs.value = 0L
                            if (pbState.currentSong != null) {
                                updateRecentlyPlayed(pbState.currentSong.id)
                                if (_uiState.value.showLyrics) {
                                    loadLyrics(pbState.currentSong)
                                }
                            } else {
                                _uiState.update {
                                    it.copy(lyrics = emptyList(), lyricsCurrentIndex = -1, lyricsCurrentSongId = null)
                                }
                            }
                        }

                        if (pbState.isPlaying) startProgressPolling() else stopProgressPolling()
                }
            }
            launch {
                svc.upcomingSongs.collect { songs ->
                    _uiState.update { it.copy(upcomingSongs = songs) }
                }
            }
            launch {
                svc.outputRouteStateFlow.collect { routeState ->
                    _uiState.update {
                        it.copy(
                            outputMode = routeState.selectedMode.name,
                            outputDevice = routeState.outputDevice,
                            hiResDirectSupported = routeState.hiResDirectSupported,
                            hiResCapabilitySummary = routeState.capabilitySummary
                        )
                    }
                }
            }
        }
    }

    private var scanJob: Job? = null
    private var lyricsJob: Job? = null

    fun quickScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLibrary = true) }
            try {
                val results = repository.scanAudioFiles(fullScan = false) { _, _, _, _ -> }

                val currentSongs = _songs.value
                val currentIds = currentSongs.map { it.id }.toSet()
                val resultIds = results.map { it.id }.toSet()
                val newSongs = results.filter { it.id !in currentIds }
                val removedIds = currentIds - resultIds
                val metadataChanged = currentSongs.size != results.size || currentSongs != results

                if (metadataChanged) {
                    _songs.value = results
                    val entities = results.map { song -> song.toEntity() }
                    withContext(Dispatchers.IO) {
                        if (removedIds.isNotEmpty()) {
                            songDao.deleteSongsByIds(removedIds.toList())
                        }
                        entities.chunked(200).forEach { chunk ->
                            songDao.insertSongs(chunk)
                        }
                    }
                }

                updateLibraryCounts(results)

                val message = when {
                    newSongs.isNotEmpty() && removedIds.isNotEmpty() -> "Added ${newSongs.size} songs, removed ${removedIds.size}"
                    newSongs.isNotEmpty() -> "Added ${newSongs.size} new songs"
                    removedIds.isNotEmpty() -> "Removed ${removedIds.size} missing songs"
                    metadataChanged -> "Library updated"
                    else -> "No changes found"
                }

                _uiState.update { it.copy(errorMessage = message) }
                delay(2000)
                _uiState.update { it.copy(errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Scan failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoadingLibrary = false) }
            }
        }
    }

    fun startFullScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = 0f, scanCount = 0) }
            
            try {
                // Use fullScan = true for "Full Rescan" to ensure MediaExtractor/MediaMetadataRetriever are used
                val results = repository.scanAudioFiles(fullScan = true) { count, albums, artists, progress ->
                    _uiState.update { it.copy(
                        scanCount = count,
                        albumCount = albums,
                        artistCount = artists,
                        scanProgress = progress
                    )}
                    service?.updateScanningProgress(progress, count, false)
                }
                
                _songs.value = results
                val entities = results.map { song ->
                    com.beatflowy.app.model.SongEntity(
                        id = song.id,
                        uriString = song.uri.toString(),
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                        format = song.format,
                        sampleRateHz = song.sampleRateHz,
                        bitDepth = song.bitDepth,
                        bitrate = song.bitrate,
                        fileSizeBytes = song.fileSizeBytes,
                        albumArtUriString = song.albumArtUri?.toString(),
                        year = song.year,
                        genre = song.genre,
                        folder = song.folder,
                        dateAdded = song.dateAdded
                    )
                }
                
                // Perform DB insertion on a background thread and handle chunks to avoid locking the UI
                withContext(Dispatchers.IO) {
                    songDao.deleteAllSongs()
                    entities.chunked(100).forEach { chunk ->
                        songDao.insertSongs(chunk)
                    }
                }

                updateLibraryCounts(results)
                _uiState.update {
                    it.copy(
                        scanProgress = 1.0f,
                        scanCount = results.size,
                        albumCount = results.map { song -> song.album }.toSet().size,
                        artistCount = results.map { song -> song.artist }.toSet().size
                    )
                }
                service?.updateScanningProgress(1.0f, results.size, true)
                delay(800)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Full scan failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionDenied = true) }
    }

    fun playSong(song: Song) {
        val list = songs.value
        val index = list.indexOf(song)
        if (index >= 0) {
            // Check if we are already playing this song to handle resume correctly
            if (_uiState.value.currentSong?.id == song.id) {
                service?.togglePlayPause()
            } else {
                service?.playList(list, index)
            }
        } else {
            if (_uiState.value.currentSong?.id == song.id) {
                service?.togglePlayPause()
            } else {
                service?.playSong(song)
            }
        }
        updateRecentlyPlayed(song.id)
        loadLyrics(song)
    }

    private fun updateRecentlyPlayed(songId: String) {
        val current = _recentlyPlayed.value.toMutableList()
        current.remove(songId)
        current.add(0, songId)
        if (current.size > 50) current.removeAt(current.size - 1)
        _recentlyPlayed.value = current
    }

    fun setLibraryView(view: LibraryView, itemName: String? = null) {
        _uiState.update { 
            val isDetailView = view in listOf(
                LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL, 
                LibraryView.FOLDER_DETAIL, LibraryView.GENRE_DETAIL, LibraryView.YEAR_DETAIL,
                LibraryView.PLAYLIST_DETAIL
            )
            it.copy(
                previousView = it.currentView,
                currentView = view, 
                selectedItemName = itemName,
                currentFolderPath = if (view == LibraryView.FOLDER_DETAIL) it.currentFolderPath else null,
                wasSearchingBeforeDetail = if (isDetailView) it.isSearchActive else it.wasSearchingBeforeDetail
            ) 
        }
    }

    fun navigateToFolder(path: String, name: String) {
        _uiState.update { 
            it.copy(
                previousView = it.currentView,
                currentView = LibraryView.FOLDER_DETAIL,
                selectedItemName = name,
                currentFolderPath = path,
                wasSearchingBeforeDetail = it.isSearchActive
            ) 
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _uiState.update { it.copy(isMultiSelectMode = enabled, selectedSongIds = emptySet()) }
    }

    fun toggleSongSelection(songId: String) {
        _uiState.update { 
            val current = it.selectedSongIds
            val updated = if (current.contains(songId)) current - songId else current + songId
            it.copy(selectedSongIds = updated)
        }
    }

    fun getNextSongPreview(): Song? {
        return service?.getNextSong()
    }

    fun deleteSelectedSongs() {
        val selectedIds = _uiState.value.selectedSongIds.toList()
        if (selectedIds.isEmpty()) return
        
        viewModelScope.launch {
            val songsToDelete = allSongs.value.filter { it.id in selectedIds }
            pendingDeleteIds = selectedIds
            val intent = repository.deleteSongs(songsToDelete.map { it.uri })
            if (intent != null) {
                _deleteRequest.value = intent
            } else {
                // Success for < Android 10 or pre-granted permissions
                onDeleteSuccess()
            }
        }
    }

    fun addSelectedToPlaylist(playlistName: String) {
        val selectedIds = _uiState.value.selectedSongIds
        if (selectedIds.isEmpty()) return
        
        viewModelScope.launch {
            val currentPlaylists = playlists.value
            val existing = currentPlaylists.find { it.name == playlistName }
            val playlist = if (existing != null) {
                existing.copy(songIds = (existing.songIds + selectedIds).toList().distinct())
            } else {
                Playlist(id = System.currentTimeMillis().toString(), name = playlistName, songIds = selectedIds.toList())
            }
            playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, playlist.songIds.joinToString(",")))
            setMultiSelectMode(false)
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            playlists.value.find { it.id == playlistId }?.let { playlist ->
                val newSongIds = playlist.songIds - songId
                playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, newSongIds.joinToString(",")))
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            playlistDao.deletePlaylist(playlistId)
            if (_uiState.value.currentView == LibraryView.PLAYLIST_DETAIL) {
                setLibraryView(LibraryView.PLAYLISTS)
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            if (playlists.value.any { it.name == name }) return@launch
            val playlist = Playlist(id = System.currentTimeMillis().toString(), name = name)
            playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, ""))
        }
    }

    fun setShowFullPlayer(show: Boolean) {
        _uiState.update { it.copy(showFullPlayer = show) }
    }

    fun toggleQueue() {
        _uiState.update { it.copy(showQueue = !it.showQueue) }
    }

    fun getUpcomingSongs(): List<Song> {
        return service?.getUpcomingSongs() ?: emptyList()
    }

    fun removeFromQueue(songId: String) {
        service?.removeFromQueue(songId)
    }

    fun moveInQueue(from: Int, to: Int) {
        service?.moveInUpcomingQueue(from, to)
    }

    fun playFromQueue(songId: String) {
        service?.playFromQueue(songId)
    }

    fun playNext(song: Song) {
        service?.playNext(song)
    }

    fun addToQueue(song: Song) {
        service?.addToQueue(song)
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            pendingDeleteIds = listOf(song.id)
            val intent = repository.deleteSongs(listOf(song.uri))
            if (intent != null) {
                _deleteRequest.value = intent
            } else {
                onDeleteSuccess()
            }
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            if (favorites.value.contains(song.id)) {
                favoriteDao.removeFavorite(song.id)
            } else {
                favoriteDao.addFavorite(FavoriteEntity(song.id))
            }
        }
    }

    fun togglePlayPause() {
        service?.let { svc ->
            val nextPlaying = !(_uiState.value.isPlaying)
            _uiState.update { it.copy(isPlaying = nextPlaying) }
            svc.togglePlayPause()
        } ?: _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun skipToNext() {
        service?.next()
    }

    fun skipToPrevious() {
        service?.previous()
    }

    fun seekTo(positionMs: Long) {
        _progressMs.value = positionMs
        service?.seekTo(positionMs)
    }

    fun toggleResampling() {
        val newValue = !_uiState.value.dsp.config.resamplerEnabled
        applyDspConfig { it.copy(resamplerEnabled = newValue) }
        _uiState.update { it.copy(resamplingEnabled = newValue) }
    }

    fun setResamplerEnabled(enabled: Boolean) {
        applyDspConfig { it.copy(resamplerEnabled = enabled) }
        _uiState.update { it.copy(resamplingEnabled = enabled) }
    }

    fun setTargetSampleRate(sampleRate: Int) {
        applyDspConfig { it.copy(targetSampleRate = sampleRate.coerceIn(44_100, 192_000)) }
    }

    fun setResamplerCutoffRatio(value: Float) {
        applyDspConfig { it.copy(resamplerCutoffRatio = value.coerceIn(0.80f, 0.995f)) }
    }

    fun setOutputMode(mode: OutputMode) {
        prefs.edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
        _uiState.update { it.copy(outputMode = mode.name) }
        service?.setOutputMode(mode)
    }

    private fun applyDspConfig(transform: (DspConfig) -> DspConfig) {
        val updated = transform(_uiState.value.dsp.config)
        _uiState.update { it.copy(dsp = it.dsp.copy(config = updated, autoEqError = null)) }
        service?.updateDspConfig(updated)
    }

    fun setPreampEnabled(enabled: Boolean) = applyDspConfig { it.copy(preampEnabled = enabled) }
    fun setPreampDb(value: Float) = applyDspConfig { it.copy(preampDb = value.coerceIn(-18f, 18f)) }
    fun setEqEnabled(enabled: Boolean) = applyDspConfig { it.copy(eqEnabled = enabled) }
    fun setBassEnabled(enabled: Boolean) = applyDspConfig { it.copy(bassEnabled = enabled) }
    fun setBassDb(value: Float) = applyDspConfig { it.copy(bassDb = value.coerceIn(-12f, 12f)) }
    fun setTrebleEnabled(enabled: Boolean) = applyDspConfig { it.copy(trebleEnabled = enabled) }
    fun setTrebleDb(value: Float) = applyDspConfig { it.copy(trebleDb = value.coerceIn(-12f, 12f)) }
    fun setBalanceEnabled(enabled: Boolean) = applyDspConfig { it.copy(balanceEnabled = enabled) }
    fun setBalance(value: Float) = applyDspConfig { it.copy(balance = value.coerceIn(-1f, 1f)) }
    fun setStereoExpansionEnabled(enabled: Boolean) = applyDspConfig { it.copy(stereoExpansionEnabled = enabled) }
    fun setStereoWidth(value: Float) = applyDspConfig { it.copy(stereoWidth = value.coerceIn(0.5f, 2f)) }
    fun setReverbEnabled(enabled: Boolean) = applyDspConfig { it.copy(reverbEnabled = enabled) }
    fun setReverbAmount(value: Float) = applyDspConfig { it.copy(reverbAmount = value.coerceIn(0f, 1f)) }

    fun setEqBandEnabled(index: Int, enabled: Boolean) {
        applyEqBand(index) { it.copy(enabled = enabled) }
    }

    fun setEqBandFrequency(index: Int, frequencyHz: Float) {
        applyEqBand(index) { it.copy(frequencyHz = frequencyHz.coerceIn(20f, 20_000f)) }
    }

    fun setEqBandGain(index: Int, gainDb: Float) {
        applyEqBand(index) { it.copy(gainDb = gainDb.coerceIn(-12f, 12f)) }
    }

    fun setEqBandQ(index: Int, q: Float) {
        applyEqBand(index) { it.copy(q = q.coerceIn(0.2f, 8f)) }
    }

    private fun applyEqBand(index: Int, transform: (ParametricEqBand) -> ParametricEqBand) {
        applyDspConfig { config ->
            config.copy(
                eqBands = config.eqBands.mapIndexed { bandIndex, band ->
                    if (bandIndex == index) transform(band) else band
                }
            )
        }
    }

    fun setAutoEqQuery(query: String) {
        _uiState.update { it.copy(dsp = it.dsp.copy(autoEqQuery = query)) }
    }

    fun searchAutoEqProfiles() {
        val query = _uiState.value.dsp.autoEqQuery.trim()
        if (query.isBlank()) {
            _uiState.update { it.copy(dsp = it.dsp.copy(autoEqResults = emptyList(), autoEqError = null)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = true, autoEqError = null)) }
            runCatching {
                autoEqRepository.searchProfiles(query)
            }.onSuccess { results ->
                _uiState.update {
                    it.copy(dsp = it.dsp.copy(autoEqLoading = false, autoEqResults = results, autoEqError = null))
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(dsp = it.dsp.copy(autoEqLoading = false, autoEqError = error.message ?: "AutoEQ search failed"))
                }
            }
        }
    }

    fun applyAutoEqProfile(summary: AutoEqProfileSummary) {
        viewModelScope.launch {
            _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = true, autoEqError = null)) }
            runCatching {
                autoEqRepository.loadProfile(summary)
            }.onSuccess { profile ->
                applyDspConfig { config ->
                    config.copy(
                        autoEqEnabled = true,
                        autoEqProfile = profile
                    )
                }
                _uiState.update {
                    it.copy(
                        dsp = it.dsp.copy(
                            autoEqLoading = false,
                            autoEqError = null,
                            autoEqQuery = profile.name
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(dsp = it.dsp.copy(autoEqLoading = false, autoEqError = error.message ?: "AutoEQ load failed"))
                }
            }
        }
    }

    fun clearAutoEqProfile() {
        applyDspConfig { it.copy(autoEqEnabled = false, autoEqProfile = null) }
        _uiState.update {
            it.copy(
                dsp = it.dsp.copy(
                    autoEqError = null,
                    autoEqResults = emptyList()
                )
            )
        }
    }

    fun toggleShuffle() {
        service?.toggleShuffle()
    }

    fun toggleRepeat() {
        service?.toggleRepeat()
    }

    fun setSortType(sortType: SortType) {
        _uiState.update { it.copy(sortType = sortType) }
    }

    fun toggleSortOrder() {
        _uiState.update { it.copy(isAscending = !it.isAscending) }
    }

    fun setViewMode(viewMode: ViewMode) {
        _uiState.update { it.copy(viewMode = viewMode) }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active) }
        if (!active) setSearchQuery("")
    }

    fun shuffleAndPlay() {
        val currentSongs = songs.value
        if (currentSongs.isNotEmpty()) {
            service?.setShuffleMode(true)
            val shuffled = currentSongs.shuffled()
            service?.playList(shuffled, 0)
        }
    }

    fun toggleLyrics() {
        _uiState.update { it.copy(showLyrics = !it.showLyrics) }
        if (_uiState.value.showLyrics && (_uiState.value.lyrics.isEmpty() || _uiState.value.lyricsCurrentSongId != _uiState.value.currentSong?.id)) {
            loadLyrics(_uiState.value.currentSong)
        }
    }

    private fun loadLyrics(song: Song?) {
        lyricsJob?.cancel()

        if (song == null) {
            _uiState.update {
                it.copy(
                    lyrics = emptyList(),
                    lyricsCurrentIndex = -1,
                    lyricsCurrentSongId = null,
                    isLoadingLyrics = false,
                    lyricsSource = null
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                lyrics = emptyList(),
                lyricsCurrentIndex = -1,
                lyricsCurrentSongId = song.id,
                isLoadingLyrics = false,
                lyricsSource = null
            )
        }

        lyricsJob = viewModelScope.launch {
            val embeddedLyrics = lyricsRepository.getEmbeddedLyrics(song)
            if (!isActive || _uiState.value.currentSong?.id != song.id) return@launch

            if (embeddedLyrics != null) {
                _uiState.update {
                    it.copy(
                        lyrics = embeddedLyrics.lines,
                        lyricsCurrentIndex = -1,
                        isLoadingLyrics = false,
                        lyricsSource = embeddedLyrics.source
                    )
                }
                return@launch
            }

            val cachedLyrics = lyricsRepository.getCachedLyrics(song)
            if (!isActive || _uiState.value.currentSong?.id != song.id) return@launch

            if (cachedLyrics != null) {
                _uiState.update {
                    it.copy(
                        lyrics = cachedLyrics.lines,
                        lyricsCurrentIndex = -1,
                        isLoadingLyrics = false,
                        lyricsSource = cachedLyrics.source
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoadingLyrics = true, lyricsSource = null) }

            val onlineLyrics = lyricsRepository.fetchOnlineLyrics(song)
            if (!isActive || _uiState.value.currentSong?.id != song.id) return@launch

            _uiState.update {
                it.copy(
                    lyrics = onlineLyrics?.lines ?: emptyList(),
                    lyricsCurrentIndex = -1,
                    isLoadingLyrics = false,
                    lyricsSource = onlineLyrics?.source
                )
            }
        }
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        _uiState.update { it.copy(lyricsOffsetMs = it.lyricsOffsetMs + deltaMs) }
    }

    fun saveLyrics(songId: String, lyricsText: String) {
        viewModelScope.launch {
            lyricsRepository.saveLyrics(songId, lyricsText)
            // Reload lyrics if it's the current song
            if (_uiState.value.currentSong?.id == songId) {
                _uiState.update {
                    it.copy(
                        lyrics = LrcParser.parse(lyricsText),
                        lyricsSource = LyricsSource.CACHE,
                        isLoadingLyrics = false
                    )
                }
            }
        }
    }

    private fun updateLyricsIndex(currentMs: Long) {
        val state = _uiState.value
        if (state.lyrics.isEmpty()) return
        
        val adjustedMs = currentMs + state.lyricsOffsetMs
        val index = state.lyrics.findLast { it.time <= adjustedMs }?.let { state.lyrics.indexOf(it) } ?: -1
        
        if (index != state.lyricsCurrentIndex) {
            _uiState.update { it.copy(lyricsCurrentIndex = index) }
        }
    }

    private val progressFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!_uiState.value.isPlaying) return
            val svc = service ?: return
            val pos = svc.currentPositionMs
            if (_progressMs.value != pos) {
                _progressMs.value = pos
                updateLyricsIndex(pos)
            }
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_TICK_MS)
        }
    }

    private fun startProgressPolling() {
        Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
        Choreographer.getInstance().postFrameCallback(progressFrameCallback)
    }

    private fun stopProgressPolling() {
        Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
    }

    fun setFirstRunComplete() {
        prefs.edit().putBoolean("first_run", false).apply()
        _uiState.update { it.copy(isFirstRun = false) }
    }

    fun resetFirstRun() {
        prefs.edit().putBoolean("first_run", true).apply()
        _uiState.update { it.copy(isFirstRun = true) }
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        serviceObserversJob?.cancel()
        libraryLoadJob?.cancel()
    }

    private companion object {
        const val FRAME_TICK_MS = 16L
        const val KEY_OUTPUT_MODE = "output_mode"
    }

    private fun updateLibraryCounts(songs: List<Song>) {
        _uiState.update {
            it.copy(
                scanCount = songs.size,
                albumCount = songs.map { song -> song.album }.toSet().size,
                artistCount = songs.map { song -> song.artist }.toSet().size
            )
        }
    }

    private fun Song.toEntity() = com.beatflowy.app.model.SongEntity(
        id = id,
        uriString = uri.toString(),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        format = format,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        bitrate = bitrate,
        fileSizeBytes = fileSizeBytes,
        albumArtUriString = albumArtUri?.toString(),
        year = year,
        genre = genre,
        folder = folder,
        dateAdded = dateAdded
    )
}

class PlayerViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            return PlayerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
