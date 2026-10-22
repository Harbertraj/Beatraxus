package com.beatraxus.app.theme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatraxus.app.repository.AppearancePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Carries a beatraxus://theme link from MainActivity to the UI, which asks before importing. */
object ThemeInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    fun offer(link: String) { _pending.value = link }
    fun consume() { _pending.value = null }
}

data class StoreUiState(
    val loading: Boolean = false,
    val entries: List<ThemeIndexEntry> = emptyList(),
    val fromCache: Boolean = false,
    val error: String? = null
)

data class ThemesUiState(
    val store: StoreUiState = StoreUiState(),
    val installed: List<ThemeSpec> = emptyList(),
    /** Id of the store theme being downloaded right now. */
    val busyId: String? = null,
    /** A short one-off message ("Applied Neon Night"), shown once. */
    val message: String? = null,
    /** Full themes of store cards (by id), loaded in the background so the gallery can show a real UI preview. */
    val previews: Map<String, ThemeSpec> = emptyMap()
)

class ThemeViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ThemeRepository(application)
    private val prefs = AppearancePreferences(application)

    private val _state = MutableStateFlow(ThemesUiState())
    val state: StateFlow<ThemesUiState> = _state.asStateFlow()

    init {
        reloadInstalled()
        refreshStore()
    }

    fun refreshStore(force: Boolean = false) {
        _state.update { it.copy(store = it.store.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val index = repo.loadIndex(force)
                _state.update { it.copy(store = StoreUiState(entries = index.entries, fromCache = index.fromCache)) }
            } catch (e: Exception) {
                _state.update { it.copy(store = it.store.copy(loading = false, error = e.message ?: "Couldn't load themes")) }
            }
        }
    }

    private val previewRequested = HashSet<String>()

    /** Loads the full theme behind each store card, one at a time, so cards fill in as they arrive. */
    fun loadPreviews(entries: List<ThemeIndexEntry>) {
        val todo = entries.filter { previewRequested.add("${it.id}-${it.version}") }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            for (entry in todo) {
                val spec = repo.loadPreviewSpec(entry)
                if (spec != null) _state.update { it.copy(previews = it.previews + (entry.id to spec)) }
            }
        }
    }

    private fun reloadInstalled() {
        viewModelScope.launch { _state.update { it.copy(installed = repo.installed()) } }
    }

    fun apply(spec: ThemeSpec) {
        viewModelScope.launch {
            // Make sure the theme's icon images are on the phone first, so the whole look appears at once.
            val iconsOk = repo.ensureIconPack(spec)
            prefs.applyTheme(spec)
            _state.update {
                it.copy(message = if (iconsOk) "Applied ${spec.name}" else "Applied ${spec.name} (some icons couldn't be downloaded)")
            }
        }
    }

    /** Built-in Default theme: the whole app look (UI only) goes back to the app's own defaults. */
    fun applyDefault() {
        viewModelScope.launch {
            prefs.resetThemeToDefault()
            _state.update { it.copy(message = "Default look restored") }
        }
    }

    /** Downloads (or updates) a store theme, saves it to Installed and applies it. */
    fun installAndApply(entry: ThemeIndexEntry) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = entry.id) }
        viewModelScope.launch {
            repo.installFromStore(entry)
                .onSuccess { spec ->
                    prefs.applyTheme(spec)
                    _state.update { it.copy(busyId = null, message = "Applied ${spec.name}") }
                    reloadInstalled()
                }
                .onFailure { e ->
                    _state.update { it.copy(busyId = null, message = e.message ?: "Couldn't download that theme") }
                }
        }
    }

    /** Saves an imported theme to Installed, optionally applying it too. */
    fun saveImported(spec: ThemeSpec, apply: Boolean) {
        viewModelScope.launch {
            repo.save(spec)
            // A shared theme carries no images; if the store has icons for its id they are fetched here.
            if (apply) {
                repo.ensureIconPack(spec)
                prefs.applyTheme(spec)
            }
            _state.update { it.copy(message = if (apply) "Applied ${spec.name}" else "Saved ${spec.name}") }
            reloadInstalled()
        }
    }

    fun remove(spec: ThemeSpec, isActive: Boolean) {
        viewModelScope.launch {
            repo.remove(spec.id)
            if (isActive) prefs.clearActiveTheme()
            _state.update { it.copy(message = "Removed ${spec.name}") }
            reloadInstalled()
        }
    }

    /** Stops following the active theme; the look stays as it is, ready to customize. */
    fun detachActive() {
        viewModelScope.launch { prefs.clearActiveTheme() }
    }

    fun parseImport(input: String): Result<ThemeSpec> = repo.parseImport(input)

    fun messageShown() = _state.update { it.copy(message = null) }
}
