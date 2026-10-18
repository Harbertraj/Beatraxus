package com.beatraxus.app.livetv

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class LiveTvUiState(
    /** The playlists the user added, as chips (code = playlist link or file). Empty until the user adds one. */
    val filters: List<LiveFilter> = emptyList(),
    val selectedCode: String = "",
    /** Ways to narrow the open playlist's channels, built from the playlist itself (its groups and countries). */
    val tabs: List<LiveTvTab> = emptyList(),
    val tab: LiveTvTab? = null,
    /** Chips of the selected [tab]; the first one ("All", empty code) shows every channel. */
    val facets: List<LiveFilter> = emptyList(),
    val facet: String = "",
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

    val selectedFacet: LiveFilter? get() = if (facet.isEmpty()) null else facets.firstOrNull { it.code == facet }

    val favoriteIds: Set<String> get() = favorites.mapTo(HashSet()) { it.id }

    /** Channels after the chip and the search box are applied. */
    val visibleChannels: List<LiveChannel>
        get() {
            var list = channels
            if (facet.isNotEmpty()) {
                list = when (tab) {
                    LiveTvTab.CATEGORY -> list.filter { it.group == facet }
                    LiveTvTab.COUNTRY -> list.filter { it.countryCode.equals(facet, ignoreCase = true) }
                    null -> list
                }
            }
            val q = query.trim()
            if (q.isNotEmpty()) {
                list = list.filter { it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true) }
            }
            return list
        }
}

private const val MAX_FACETS = 80

private fun SavedPlaylist.toFilter() = LiveFilter(code = url, label = name, emoji = if (url.startsWith("file:")) "📁" else "📄")

/** Tabs worth showing for a playlist: only ones that would actually split its channels. */
private fun tabsFor(channels: List<LiveChannel>): List<LiveTvTab> = buildList {
    if (channels.asSequence().map { it.group }.filter { it.isNotBlank() }.distinct().take(2).count() >= 2) add(LiveTvTab.CATEGORY)
    if (channels.asSequence().mapNotNull { it.countryCode }.distinct().take(2).count() >= 2) add(LiveTvTab.COUNTRY)
}

private fun facetsFor(channels: List<LiveChannel>, tab: LiveTvTab?): List<LiveFilter> {
    if (tab == null) return emptyList()
    val counts: Map<String, Int> = when (tab) {
        LiveTvTab.CATEGORY -> channels.filter { it.group.isNotBlank() }.groupingBy { it.group }.eachCount()
        LiveTvTab.COUNTRY -> channels.mapNotNull { it.countryCode?.uppercase(Locale.ROOT) }.groupingBy { it }.eachCount()
    }
    val chips = counts.entries.sortedByDescending { it.value }.take(MAX_FACETS).map { (key, _) ->
        when (tab) {
            LiveTvTab.CATEGORY -> LiveFilter(key, key, "🏷️")
            LiveTvTab.COUNTRY -> LiveFilter(
                key,
                Locale("", key).getDisplayCountry(Locale.ENGLISH).ifBlank { key },
                flagEmoji(key)
            )
        }
    }
    return listOf(LiveFilter("", "All", "📡")) + chips
}

/** The loaded [list] with tabs and chips rebuilt from it; the open tab and chip are kept when they still exist. */
private fun LiveTvUiState.withChannels(list: List<LiveChannel>): LiveTvUiState {
    val newTabs = tabsFor(list)
    val newTab = tab?.takeIf { it in newTabs } ?: newTabs.firstOrNull()
    val newFacets = facetsFor(list, newTab)
    return copy(
        channels = list,
        tabs = newTabs,
        tab = newTab,
        facets = newFacets,
        facet = facet.takeIf { f -> newFacets.any { it.code == f } } ?: "",
        isLoading = false,
        isRefreshing = false,
        error = null
    )
}

private fun LiveTvUiState.withoutChannels(): LiveTvUiState =
    copy(channels = emptyList(), tabs = emptyList(), tab = null, facets = emptyList(), facet = "")

/**
 * State holder for the playlists screen. Kept apart from PlayerViewModel on purpose: it only deals with the user's
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

    /** Adds the pasted link and opens it. Returns false (and shows why) when it isn't a usable link. */
    fun addPlaylist(raw: String): Boolean {
        val url = cleanPlaylistUrl(raw)
        if (url == null) {
            _state.update { it.copy(inputError = "Paste a full link that starts with https:// (or http://)") }
            return false
        }
        val playlists = repository.addPlaylist(SavedPlaylist(url, playlistLabel(url)))
        openPlaylist(url, playlists)
        return true
    }

    /** Imports an M3U file the user picked and opens it. */
    fun importPlaylistFile(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "My playlist"
                    val input = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't open that file")
                    input.use { repository.importFile(name, it) }
                }
            }
            result.fold(
                onSuccess = { playlist -> openPlaylist(playlist.url, repository.playlists()) },
                onFailure = { e -> _state.update { it.copy(inputError = e.message ?: "Couldn't read that file") } }
            )
        }
    }

    private fun openPlaylist(url: String, playlists: List<SavedPlaylist>) {
        repository.setSelectedUrl(url)
        _state.update {
            it.withoutChannels().copy(
                filters = playlists.map { p -> p.toFilter() },
                selectedCode = url,
                query = "",
                inputError = null
            )
        }
        load(force = true, keepList = false)
    }

    fun clearInputError() {
        if (_state.value.inputError != null) _state.update { it.copy(inputError = null) }
    }

    fun selectFilter(code: String) {
        if (code == _state.value.selectedCode) return
        repository.setSelectedUrl(code)
        _state.update { it.withoutChannels().copy(selectedCode = code, query = "") }
        load(force = false, keepList = false)
    }

    fun selectTab(tab: LiveTvTab) {
        _state.update { it.copy(tab = tab, facets = facetsFor(it.channels, tab), facet = "") }
    }

    fun selectFacet(code: String) = _state.update { it.copy(facet = code) }

    /** Removes the selected playlist (and its saved copy) and falls back to another one, if any. */
    fun removeSelected() {
        val url = _state.value.selectedCode
        if (url.isEmpty()) return
        val playlists = repository.removePlaylist(url)
        val next = playlists.firstOrNull()?.url.orEmpty()
        repository.setSelectedUrl(next.ifEmpty { null })
        loadJob?.cancel()
        _state.update {
            it.withoutChannels().copy(
                filters = playlists.map { p -> p.toFilter() },
                selectedCode = next,
                query = "",
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
            _state.update { it.withoutChannels().copy(isLoading = false, isRefreshing = false, error = null) }
            return
        }
        _state.update {
            it.copy(isLoading = !keepList, isRefreshing = keepList, error = null)
                .let { s -> if (keepList) s else s.withoutChannels() }
        }
        loadJob = viewModelScope.launch {
            val result = repository.load(LiveSource(url), forceRefresh = force)
            // The user may have switched playlists while this was loading.
            if (_state.value.selectedCode != url) return@launch
            result.fold(
                onSuccess = { list -> _state.update { it.withChannels(list) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = if (it.channels.isEmpty()) {
                                "Couldn't open this link. Check it and your connection, then try again."
                            } else null
                        )
                    }
                    android.util.Log.w("LiveTvVM", "load failed: ${e.message}")
                }
            )
        }
    }
}
