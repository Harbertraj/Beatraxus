package com.beatraxus.app.livetv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveTvUiState(
    /** The playlists the user added, as chips (code = playlist address). Empty until the user pastes one. */
    val filters: List<LiveFilter> = emptyList(),
    val selectedCode: String = "",
    val query: String = "",
    val channels: List<LiveChannel> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val inputError: String? = null,
    val favorites: List<LiveChannel> = emptyList(),
    val recents: List<LiveChannel> = emptyList()
) {
    val selectedFilter: LiveFilter? get() = filters.firstOrNull { it.code == selectedCode }

    val favoriteIds: Set<String> get() = favorites.mapTo(HashSet()) { it.id }

    /** Channels after the search box is applied. */
    val visibleChannels: List<LiveChannel>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return channels
            return channels.filter { it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true) }
        }
}

private fun SavedPlaylist.toFilter() = LiveFilter(code = url, label = name, emoji = "📄")

/**
 * State holder for the playlist screen. Kept apart from PlayerViewModel on purpose: it only deals with the user's
 * playlists and their channels, playback goes through PlayerViewModel.playLiveChannels() into the normal video player.
 */
class LiveTvViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LiveTvRepository(application)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        if (_state.value.selectedCode.isNotEmpty()) load(force = false, keepList = false)
    }

    private fun initialState(): LiveTvUiState {
        val playlists = repository.playlists()
        val saved = repository.selectedUrl()
        val selected = playlists.firstOrNull { it.url == saved }?.url ?: playlists.firstOrNull()?.url.orEmpty()
        return LiveTvUiState(
            filters = playlists.map { it.toFilter() },
            selectedCode = selected,
            isLoading = selected.isNotEmpty(),
            favorites = repository.favorites(),
            recents = repository.recents()
        )
    }

    /** Adds the pasted playlist address and opens it. Returns false (and shows why) when it isn't a usable link. */
    fun addPlaylist(raw: String): Boolean {
        val url = cleanPlaylistUrl(raw)
        if (url == null) {
            _state.update { it.copy(inputError = "Paste a full link that starts with https:// (or http://)") }
            return false
        }
        val playlists = repository.addPlaylist(SavedPlaylist(url, playlistLabel(url)))
        repository.setSelectedUrl(url)
        _state.update {
            it.copy(filters = playlists.map { p -> p.toFilter() }, selectedCode = url, query = "", inputError = null)
        }
        load(force = true, keepList = false)
        return true
    }

    fun clearInputError() {
        if (_state.value.inputError != null) _state.update { it.copy(inputError = null) }
    }

    fun selectFilter(code: String) {
        if (code == _state.value.selectedCode) return
        repository.setSelectedUrl(code)
        _state.update { it.copy(selectedCode = code, query = "") }
        load(force = false, keepList = false)
    }

    /** Removes the selected playlist (and its cached copy) and falls back to another one, if any. */
    fun removeSelected() {
        val url = _state.value.selectedCode
        if (url.isEmpty()) return
        val playlists = repository.removePlaylist(url)
        val next = playlists.firstOrNull()?.url.orEmpty()
        repository.setSelectedUrl(next.ifEmpty { null })
        loadJob?.cancel()
        _state.update {
            it.copy(
                filters = playlists.map { p -> p.toFilter() },
                selectedCode = next,
                query = "",
                channels = emptyList(),
                isLoading = false,
                isRefreshing = false,
                error = null
            )
        }
        if (next.isNotEmpty()) load(force = false, keepList = false)
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun refresh() = load(force = true, keepList = true)

    fun retry() = load(force = true, keepList = false)

    fun toggleFavorite(channel: LiveChannel) {
        val makeFavorite = channel.id !in _state.value.favoriteIds
        val updated = repository.setFavorite(channel, makeFavorite)
        _state.update { it.copy(favorites = updated) }
    }

    /** Called when a channel is opened, so it shows up under "Recently watched". */
    fun onChannelPlayed(channel: LiveChannel) {
        _state.update { it.copy(recents = repository.addRecent(channel)) }
    }

    fun clearRecents() {
        repository.clearRecents()
        _state.update { it.copy(recents = emptyList()) }
    }

    private fun load(force: Boolean, keepList: Boolean) {
        loadJob?.cancel()
        val url = _state.value.selectedCode
        if (url.isEmpty()) {
            _state.update { it.copy(isLoading = false, isRefreshing = false, channels = emptyList(), error = null) }
            return
        }
        _state.update {
            it.copy(
                isLoading = !keepList,
                isRefreshing = keepList,
                error = null,
                channels = if (keepList) it.channels else emptyList()
            )
        }
        loadJob = viewModelScope.launch {
            val result = repository.load(LiveSource(url), forceRefresh = force)
            // The user may have switched playlists while this was loading.
            if (_state.value.selectedCode != url) return@launch
            result.fold(
                onSuccess = { list ->
                    _state.update { it.copy(channels = list, isLoading = false, isRefreshing = false, error = null) }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = if (it.channels.isEmpty()) {
                                "Couldn't open this playlist. Check the link and your connection, then try again."
                            } else null
                        )
                    }
                    android.util.Log.w("LiveTvVM", "load failed: ${e.message}")
                }
            )
        }
    }
}
