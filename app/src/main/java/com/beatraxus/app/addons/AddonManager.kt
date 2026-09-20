package com.beatraxus.app.addons

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

object AddonManager {

    private const val PREFS_NAME = "beatraxus_addons"
    private const val KEY_ADDED_IDS = "added_addon_ids"

    private var _allAddons = mutableListOf<MusicServiceAddon>()

    private val _addedIds = MutableStateFlow<Set<String>>(emptySet())
    val addedIds: StateFlow<Set<String>> = _addedIds.asStateFlow()

    private val _availableAddons = MutableStateFlow<List<MusicServiceAddon>>(emptyList())
    val availableAddons: StateFlow<List<MusicServiceAddon>> = _availableAddons.asStateFlow()

    private val _addedAddons = MutableStateFlow<List<MusicServiceAddon>>(emptyList())
    val addedAddons: StateFlow<List<MusicServiceAddon>> = _addedAddons.asStateFlow()

    private lateinit var appContext: Context
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext

        loadAddonsFromDisk()

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _addedIds.value = prefs.getStringSet(KEY_ADDED_IDS, emptySet())?.toSet() ?: emptySet()
        refreshLists()

        addedAddons.value.forEach { it.connect(appContext) }
        initialized = true
    }

    private fun loadAddonsFromDisk() {
        _allAddons.clear()
        val addonsDir = File(appContext.filesDir, "addons")
        if (!addonsDir.exists()) return

        val dirs = addonsDir.listFiles() ?: return
        for (dir in dirs) {
            if (dir.isDirectory) {
                val manifestFile = File(dir, "manifest.json")
                if (manifestFile.exists()) {
                    try {
                        val json = JSONObject(manifestFile.readText())
                        val id = json.getString("id")
                        val displayName = json.getString("displayName")
                        val authType = json.getString("authType")
                        val protocol = json.getString("protocol")

                        val addon = createAddonInstance(id, displayName, protocol, authType)
                        if (addon != null) {
                            _allAddons.add(addon)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun createAddonInstance(id: String, displayName: String, protocol: String, authType: String): MusicServiceAddon? {
        return when (protocol) {
            "jellyfin" -> JellyfinAddon(id, displayName, authType)
            "subsonic" -> SubsonicAddon(id, displayName, authType)
            "plex" -> PlexAddon(id, displayName, authType)
            "emby" -> EmbyAddon(id, displayName, authType)
            else -> null
        }
    }

    fun installAddon(zipUri: Uri): Result<AddonPackage> {
        val result = AddonInstaller.installAddon(appContext, zipUri)
        if (result.isSuccess) {
            val pkg = result.getOrNull()
            if (pkg != null) {
                val addon = createAddonInstance(
                    pkg.manifest.id,
                    pkg.manifest.displayName,
                    pkg.manifest.protocol,
                    pkg.manifest.authType
                )
                if (addon != null) {
                    _allAddons.removeAll { it.id == pkg.manifest.id }
                    _allAddons.add(addon)
                    
                    refreshLists()
                }
            }
        }
        return result
    }

    fun addAddon(context: Context, addon: MusicServiceAddon) {
        _addedIds.value = _addedIds.value + addon.id
        persist()
        refreshLists()
        addon.connect(context)
    }

    fun uninstallAddon(id: String) {
        AddonInstaller.uninstallAddon(appContext, id)
        _allAddons.removeAll { it.id == id }
        _addedIds.value = _addedIds.value - id
        persist()
        refreshLists()
    }

    fun removeAddon(addon: MusicServiceAddon) {
        addon.disconnect()
        _addedIds.value = _addedIds.value - addon.id
        persist()
        
        AddonInstaller.uninstallAddon(appContext, addon.id)
        _allAddons.removeAll { it.id == addon.id }
        refreshLists()
    }

    private fun refreshLists() {
        _addedAddons.value = _allAddons.filter { it.id in _addedIds.value }
        _availableAddons.value = _allAddons.filter { it.id !in _addedIds.value }
    }

    fun findById(id: String): MusicServiceAddon? = _allAddons.find { it.id == id }

    private fun persist() {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ADDED_IDS, _addedIds.value)
            .apply()
    }
}
