package com.beatraxus.app.addons

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException

object AddonManager {

    private const val PREFS_NAME = "beatraxus_addons"
    private const val KEY_ADDED_IDS = "added_addon_ids"
    private val VALID_ID = Regex("[A-Za-z0-9_]{1,64}")

    private var _allAddons = mutableListOf<MusicServiceAddon>()
    private val legacyRemovedIds = mutableSetOf<String>()

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
        if (legacyRemovedIds.isNotEmpty()) {
            _addedIds.value = _addedIds.value - legacyRemovedIds
            persist()
        }
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
            if (!dir.isDirectory) continue
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) continue
            try {
                val json = JSONObject(manifestFile.readText())
                val id = json.getString("id")
                val displayName = json.getString("displayName")
                val authType = json.getString("authType")
                val protocol = json.getString("protocol")
                if (protocol == "youtube") {
                    legacyRemovedIds += id
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().remove("yt_api_key_$id").apply()
                    AddonCredentialStore.clear(appContext, id)
                    dir.deleteRecursively()
                    continue
                }
                createAddonInstance(id, displayName, protocol, authType)?.let(_allAddons::add)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createAddonInstance(id: String, displayName: String, protocol: String, authType: String): MusicServiceAddon? {
        return when (protocol) {
            "jellyfin" -> JellyfinAddon(id, displayName, authType)
            "subsonic" -> SubsonicAddon(id, displayName, authType)
            "plex" -> PlexAddon(id, displayName, authType)
            "emby" -> EmbyAddon(id, displayName, authType)
            "archive_org" -> ArchiveOrgAddon(id, displayName, authType)
            "jamendo" -> JamendoAddon(id, displayName, authType)
            "stremio" -> StremioAddon(id, displayName, authType)
            "ytxtube" -> YtxTubeAddon(id, displayName, authType)
            else -> null
        }
    }

    /** Installs a remote declarative manifest only when its protocol has a built-in Kotlin adapter. */
    fun installRemoteManifest(manifest: AddonManifest): Result<MusicServiceAddon> = runCatching {
        check(initialized) { "AddonManager.init(context) must be called first" }
        require(VALID_ID.matches(manifest.id)) { "Invalid add-on ID" }
        require(manifest.displayName.isNotBlank() && manifest.displayName.length <= 80) { "Invalid add-on name" }
        val addon = createAddonInstance(manifest.id, manifest.displayName, manifest.protocol, manifest.authType)
            ?: throw IllegalArgumentException("No built-in adapter for protocol '${manifest.protocol}'")

        val existing = findById(manifest.id)
        if (existing != null) {
            val existingManifest = File(File(appContext.filesDir, "addons"), manifest.id).resolve("manifest.json")
            val existingProtocol = if (existingManifest.isFile) {
                JSONObject(existingManifest.readText()).optString("protocol")
            } else ""
            require(existingProtocol == manifest.protocol) { "This add-on ID is already used by another protocol" }
            return@runCatching existing
        }

        val addonsDir = File(appContext.filesDir, "addons")
        if (!addonsDir.exists() && !addonsDir.mkdirs()) throw IOException("Could not create add-ons directory")
        val targetDir = File(addonsDir, manifest.id)
        if (targetDir.exists()) throw IOException("An add-on with this ID already exists")
        val stagingDir = File(addonsDir, ".remote_${manifest.id}_${System.nanoTime()}")
        if (!stagingDir.mkdirs()) throw IOException("Could not create add-on staging directory")
        try {
            val json = JSONObject()
                .put("id", manifest.id)
                .put("displayName", manifest.displayName)
                .put("mediaType", manifest.mediaType)
                .put("protocol", manifest.protocol)
                .put("authType", manifest.authType)
            File(stagingDir, "manifest.json").writeText(json.toString())
            if (!stagingDir.renameTo(targetDir)) throw IOException("Could not install remote add-on")
        } catch (error: Exception) {
            stagingDir.deleteRecursively()
            throw error
        }
        _allAddons.add(addon)
        refreshLists()
        addon
    }

    fun installAddon(zipUri: Uri): Result<AddonPackage> {
        val result = AddonInstaller.installAddon(appContext, zipUri)
        if (result.isSuccess) {
            result.getOrNull()?.let { pkg ->
                createAddonInstance(pkg.manifest.id, pkg.manifest.displayName, pkg.manifest.protocol, pkg.manifest.authType)?.let { addon ->
                    _allAddons.removeAll { it.id == pkg.manifest.id }
                    _allAddons.add(addon)
                    refreshLists()
                }
            }
        }
        return result
    }

    fun installPreset(preset: AddonPreset): MusicServiceAddon? {
        val existing = findById(preset.id)
        if (existing != null) return existing
        return try {
            val dir = File(File(appContext.filesDir, "addons"), preset.id)
            if (!dir.exists() && !dir.mkdirs()) return null
            val manifest = JSONObject()
                .put("id", preset.id)
                .put("displayName", preset.displayName)
                .put("mediaType", preset.mediaType)
                .put("protocol", preset.protocol)
                .put("authType", preset.authType)
            File(dir, "manifest.json").writeText(manifest.toString())
            val addon = createAddonInstance(preset.id, preset.displayName, preset.protocol, preset.authType)
            if (addon != null) {
                _allAddons.add(addon)
                refreshLists()
            }
            addon
        } catch (e: Exception) {
            null
        }
    }

    fun addAddon(context: Context, addon: MusicServiceAddon) {
        _addedIds.value = _addedIds.value + addon.id
        persist()
        refreshLists()
        addon.connect(context)
    }

    /** Disable without deleting the provider's saved manifest or credentials. */
    fun disableAddon(id: String) {
        findById(id)?.disconnect()
        _addedIds.value = _addedIds.value - id
        persist()
        refreshLists()
    }

    fun uninstallAddon(id: String) {
        findById(id)?.disconnect()
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
