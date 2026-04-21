package com.beatflowy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import com.beatflowy.app.BeatraxusApplication
import com.beatflowy.app.model.PlaylistEntity
import com.beatflowy.app.model.FavoriteEntity
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.Playlist
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SortType
import com.beatflowy.app.model.ViewMode
import com.beatflowy.app.repository.MusicRepository
import com.beatflowy.app.service.AudioPlaybackService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val lyricsRepository = com.beatflowy.app.repository.LyricsRepository(application)

    private val database = (application as BeatraxusApplication).database
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val songDao = database.songDao()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

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

    private val _lyrics = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val lyrics: StateFlow<List<Pair<Long, String>>> = _lyrics.asStateFlow()

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
    private var progressJob: Job? = null

    fun attachService(svc: AudioPlaybackService) {
        service = svc
        viewModelScope.launch {
            svc.audioStateFlow.collect { audioState ->
                _uiState.update { it.copy(
                    equalizerEnabled = audioState.equalizerActive,
                    inputSampleRate = audioState.sampleRate,
                    bitDepth = audioState.bitDepth
                )}
            }
        }
        viewModelScope.launch {
            svc.playbackStateFlow.collect { pbState ->
                val prevSongId = _uiState.value.currentSong?.id
                val pos = svc.currentPositionMs
                _uiState.update {
                    it.copy(
                        isPlaying = pbState.isPlaying,
                        currentSong = pbState.currentSong,
                        shuffleMode = pbState.shuffleMode,
                        repeatMode = pbState.repeatMode.ordinal,
                        progressMs = when {
                            pbState.currentSong == null -> 0L
                            pbState.currentSong?.id != prevSongId -> 0L
                            else -> pos
                        }
                    )
                }

                if (pbState.currentSong?.id != prevSongId) {
                    if (pbState.currentSong == null) {
                        _lyrics.value = emptyList()
                    } else {
                        val song = pbState.currentSong
                        updateRecentlyPlayed(song.id)
                        viewModelScope.launch {
                            _lyrics.value = lyricsRepository.fetchLyrics(song)
                        }
                    }
                }

                if (pbState.isPlaying) startProgressPolling() else stopProgressPolling()
            }
        }
        viewModelScope.launch {
            svc.upcomingSongs.collect { songs ->
                _uiState.update { it.copy(upcomingSongs = songs) }
            }
        }
    }

    fun loadLibrary() {
        val prefs = getApplication<Application>().getSharedPreferences("beatraxus", Application.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run", true)

        viewModelScope.launch {
            if (_uiState.value.isScanning) return@launch

            // Try to load from cache first
            val cachedEntities = songDao.getAllSongs()
            if (cachedEntities.isNotEmpty()) {
                _songs.value = cachedEntities.map { entity ->
                    Song(
                        id = entity.id,
                        uri = android.net.Uri.parse(entity.uriString),
                        title = entity.title,
                        artist = entity.artist,
                        album = entity.album,
                        durationMs = entity.durationMs,
                        format = entity.format,
                        sampleRateHz = entity.sampleRateHz,
                        bitDepth = entity.bitDepth,
                        bitrate = entity.bitrate,
                        fileSizeBytes = entity.fileSizeBytes,
                        albumArtUri = entity.albumArtUriString?.let { android.net.Uri.parse(it) },
                        year = entity.year,
                        genre = entity.genre,
                        folder = entity.folder,
                        dateAdded = entity.dateAdded
                    )
                }
                if (isFirstRun) prefs.edit().putBoolean("first_run", false).apply()
                return@launch
            }

            if (isFirstRun) {
                _uiState.update { it.copy(isScanning = true, scanProgress = 0f, scanCount = 0) }
            } else {
                _uiState.update { it.copy(isLoadingLibrary = true) }
            }

            try {
                // Perform full scan
                val results = repository.scanAudioFiles(fullScan = true) { count, albums, artists, progress ->
                    if (isFirstRun) {
                        _uiState.update { it.copy(
                            scanCount = count,
                            albumCount = albums,
                            artistCount = artists,
                            scanProgress = progress
                        )}
                        service?.updateScanningProgress(progress, count, false)
                    }
                }
                
                _songs.value = results
                
                // Save to DB
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
                songDao.insertSongs(entities)
                
                if (isFirstRun && results.isNotEmpty()) {
                    prefs.edit().putBoolean("first_run", false).apply()
                }
                
                if (isFirstRun) {
                    service?.updateScanningProgress(1.0f, results.size, true)
                }
            } catch (e: Exception) {
                if (isFirstRun) {
                    _uiState.update { it.copy(errorMessage = "Failed to load library: ${e.message}") }
                }
            } finally {
                if (isFirstRun) {
                    delay(500)
                    _uiState.update { it.copy(isScanning = false) }
                } else {
                    _uiState.update { it.copy(isLoadingLibrary = false) }
                }
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
            it.copy(
                currentView = view, 
                selectedItemName = itemName,
                currentFolderPath = if (view == LibraryView.FOLDER_DETAIL) it.currentFolderPath else null
            ) 
        }
    }

    fun navigateToFolder(path: String, name: String) {
        _uiState.update { 
            it.copy(
                currentView = LibraryView.FOLDER_DETAIL,
                selectedItemName = name,
                currentFolderPath = path
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
            songDao.deleteSongsByIds(selectedIds)
            _songs.update { currentSongs ->
                currentSongs.filterNot { it.id in selectedIds }
            }
            setMultiSelectMode(false)
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

    fun toggleLyrics() {
        _uiState.update { it.copy(showLyrics = !it.showLyrics, showQueue = false) }
    }

    fun toggleQueue() {
        _uiState.update { it.copy(showQueue = !it.showQueue, showLyrics = false) }
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
        // For now, just remove from list if it's there
        viewModelScope.launch {
            // In a real app, this would delete from MediaStore or file system
            _songs.value = _songs.value.filter { it.id != song.id }
            // If it's the current song, skip to next
            if (uiState.value.currentSong?.id == song.id) {
                skipToNext()
            }
            // Remove from queue
            service?.removeFromQueue(song.id)
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
        service?.togglePlayPause()
            ?: _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun skipToNext() {
        service?.next()
    }

    fun skipToPrevious() {
        service?.previous()
    }

    fun seekTo(positionMs: Long) {
        service?.seekTo(positionMs)
        _uiState.update { it.copy(progressMs = positionMs) }
    }

    fun toggleResampling() {
        val newValue = !_uiState.value.resamplingEnabled
        _uiState.update { it.copy(resamplingEnabled = newValue) }
    }

    fun setOutputMode(mode: com.beatflowy.app.engine.OutputMode) {
        _uiState.update { it.copy(outputMode = mode.name) }
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

    fun toggleEqualizer() {
        val enabled = !_uiState.value.equalizerEnabled
        _uiState.update { it.copy(equalizerEnabled = enabled) }
        service?.setEqualizerEnabled(enabled)
    }

    fun setEqBandGain(band: Int, gain: Float) {
        val newGains = _uiState.value.eqGains.copyOf()
        newGains[band] = gain.coerceIn(-12f, 12f)
        _uiState.update { it.copy(eqGains = newGains) }
        service?.setEqBandGain(band, gain)
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(120)
                val svc = service ?: break
                if (!_uiState.value.isPlaying) continue
                val pos = svc.currentPositionMs
                _uiState.update { cur ->
                    if (!cur.isPlaying) cur else cur.copy(progressMs = pos)
                }
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
    }
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
