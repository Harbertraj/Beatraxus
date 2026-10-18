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
    val tab: LiveTvTab = LiveTvTab.COUNTRY,
    val filters: List<LiveFilter> = emptyList(),
    val selectedCode: String = "",
    val query: String = "",
    val channels: List<LiveChannel> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
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

/**
 * State holder for the Live TV screen. Kept apart from PlayerViewModel on purpose: it only deals with the channel
 * catalogue, playback goes through PlayerViewModel.playLiveChannels() into the normal video player.
 */
class LiveTvViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LiveTvRepository(application)

    /** Last chip picked per tab, so switching tabs back and forth keeps each tab's choice. */
    private val chosen = HashMap<LiveTvTab, String>()

    private val _state = MutableStateFlow(
        LiveTvUiState(
            tab = LiveTvTab.COUNTRY,
            filters = LiveTvCatalog.filters(LiveTvTab.COUNTRY),
            selectedCode = LiveTvCatalog.defaultCode(LiveTvTab.COUNTRY),
            favorites = repository.favorites(),
            recents = repository.recents()
        )
    )
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(force = false, keepList = false)
    }

    fun selectTab(tab: LiveTvTab) {
        if (tab == _state.value.tab) return
        chosen[_state.value.tab] = _state.value.selectedCode
        val code = chosen[tab] ?: LiveTvCatalog.defaultCode(tab)
        _state.update { it.copy(tab = tab, filters = LiveTvCatalog.filters(tab), selectedCode = code, query = "") }
        load(force = false, keepList = false)
    }

    fun selectFilter(code: String) {
        if (code == _state.value.selectedCode) return
        _state.update { it.copy(selectedCode = code, query = "") }
        load(force = false, keepList = false)
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
        val s = _state.value
        val source = LiveSource(s.tab, s.selectedCode)
        _state.update {
            it.copy(
                isLoading = !keepList,
                isRefreshing = keepList,
                error = null,
                channels = if (keepList) it.channels else emptyList()
            )
        }
        loadJob = viewModelScope.launch {
            val result = repository.load(source, forceRefresh = force)
            // The user may have switched chips while this was loading.
            val now = _state.value
            if (now.tab != source.tab || now.selectedCode != source.code) return@launch
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
                                "Couldn't load channels. Check your connection and try again."
                            } else null
                        )
                    }
                    android.util.Log.w("LiveTvVM", "load failed: ${e.message}")
                }
            )
        }
    }
}
