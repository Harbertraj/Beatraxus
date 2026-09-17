package com.beatraxus.app.addons

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central place that knows which addon files exist and which ones the user has
 * added. Nothing in the UI or PlayerViewModel needs to know Spotify/YouTube
 * Music/Apple Music/Amazon Music by name — it just asks AddonManager for
 * "available" and "added" lists.
 *
 * To ship a brand-new streaming addon later: drop the new addon file in this
 * package, add ONE line to `allAddons` below, done. No screen/ViewModel edits.
 */
object AddonManager {

    private const val PREFS_NAME = "beatraxus_addons"
    private const val KEY_ADDED_IDS = "added_addon_ids"

    // Register every addon file that ships with the app here.
    // Each addon is only *usable* once the user taps "Add" for it — see addedIds below.
    private lateinit var allAddons: List<MusicServiceAddon>
    private var initialized = false

    private val _addedIds = MutableStateFlow<Set<String>>(emptySet())
    val addedIds: StateFlow<Set<String>> = _addedIds.asStateFlow()

    // Reactive lists, kept in sync with _addedIds so Compose recomposes automatically.
    private val _availableAddons = MutableStateFlow<List<MusicServiceAddon>>(emptyList())
    val availableAddons: StateFlow<List<MusicServiceAddon>> = _availableAddons.asStateFlow()

    private val _addedAddons = MutableStateFlow<List<MusicServiceAddon>>(emptyList())
    val addedAddons: StateFlow<List<MusicServiceAddon>> = _addedAddons.asStateFlow()

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext

        allAddons = listOf(
            SpotifyAddon(),
            YoutubeMusicAddon(),
            AppleMusicAddon(),
            AmazonMusicAddon()
        )

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _addedIds.value = prefs.getStringSet(KEY_ADDED_IDS, emptySet())?.toSet() ?: emptySet()
        refreshLists()

        // Reconnect any addon the user had already added, on process start.
        addedAddons.value.forEach { it.connect(appContext) }
        initialized = true
    }

    fun addAddon(context: Context, addon: MusicServiceAddon) {
        _addedIds.value = _addedIds.value + addon.id
        persist()
        refreshLists()
        addon.connect(context)
    }

    fun removeAddon(addon: MusicServiceAddon) {
        addon.disconnect()
        _addedIds.value = _addedIds.value - addon.id
        persist()
        refreshLists()
    }

    private fun refreshLists() {
        _addedAddons.value = allAddons.filter { it.id in _addedIds.value }
        _availableAddons.value = allAddons.filter { it.id !in _addedIds.value }
    }

    fun findById(id: String): MusicServiceAddon? = allAddons.find { it.id == id }

    private fun persist() {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ADDED_IDS, _addedIds.value)
            .apply()
    }
}
