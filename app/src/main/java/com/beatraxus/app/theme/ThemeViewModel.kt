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
    val message: String? = null
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

    private fun reloadInstalled() {
        viewModelScope.launch { _state.update { it.copy(installed = repo.installed()) } }
    }

    fun apply(spec: ThemeSpec) {
        viewModelScope.launch {
            prefs.applyTheme(spec)
            _state.update { it.copy(message = "Applied ${spec.name}") }
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
            if (apply) prefs.applyTheme(spec)
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
