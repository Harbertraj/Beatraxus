package com.beatraxus.app.addons.plugins

import android.content.Context
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.addons.AddonManager
import com.beatraxus.app.addons.AddonManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Hybrid plugin catalogue. Repository data is declarative JSON only: this manager never downloads,
 * loads, evaluates, or installs DEX/JAR/SO/native executable code.
 */
object PluginRepositoryManager {
    private const val PREFS = "beatraxus_plugin_center"
    private const val KEY_REPOSITORIES = "repositories_v1"
    private const val MAX_INDEX_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private const val MAX_REPOSITORIES = 30
    private const val SCHEMA_VERSION = 1

    private val mutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var initialized = false

    private val _repositories = MutableStateFlow<List<PluginRepository>>(emptyList())
    val repositories: StateFlow<List<PluginRepository>> = _repositories.asStateFlow()

    private val _catalog = MutableStateFlow<List<PluginCatalogEntry>>(emptyList())
    val catalog: StateFlow<List<PluginCatalogEntry>> = _catalog.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        AddonManager.init(appContext)
        loadPersistedState()
        initialized = true
        // Saved repositories are restored without their plugin lists, so reload each index in the
        // background; otherwise the catalogue stays empty until the user taps REFRESH.
        backgroundScope.launch {
            _repositories.value.map { it.id }.forEach { refreshRepository(it) }
        }
    }

    suspend fun addRepository(input: String): Result<PluginRepository> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            mutex.withLock {
                val candidates = repositoryIndexCandidates(input)
                var lastError: Exception? = null
                var parsed: PluginRepository? = null
                for (candidate in candidates) {
                    try {
                        val json = fetchJson(candidate, MAX_INDEX_BYTES)
                        parsed = PluginRepositoryCodec.parseIndex(candidate, json)
                        break
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                val repository = parsed ?: throw (lastError ?: IllegalArgumentException("Repository index not found"))
                val updated = (_repositories.value.filterNot { it.id == repository.id } + repository)
                    .takeLast(MAX_REPOSITORIES)
                _repositories.value = updated
                refreshCatalog()
                persistRepositories()
                repository
            }
        }
    }

    suspend fun refreshRepository(repositoryId: String): Result<PluginRepository> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            mutex.withLock {
                val old = _repositories.value.firstOrNull { it.id == repositoryId }
                    ?: throw IllegalArgumentException("Repository not found")
                val json = fetchJson(old.indexUrl, MAX_INDEX_BYTES)
                val refreshed = PluginRepositoryCodec.parseIndex(old.indexUrl, json)
                _repositories.value = _repositories.value.map { if (it.id == repositoryId) refreshed else it }
                refreshCatalog()
                persistRepositories()
                refreshed
            }
        }
    }

    suspend fun installPlugin(pluginId: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            mutex.withLock {
                val entry = _catalog.value.firstOrNull { it.id == pluginId }
                    ?: throw IllegalArgumentException("Plugin is no longer in the repository catalogue")
                val manifestJson = fetchJson(entry.manifestUrl, MAX_MANIFEST_BYTES)
                val manifest = PluginRepositoryCodec.parseManifest(manifestJson)
                require(manifest.id == entry.id) { "Manifest ID does not match repository entry" }
                require(manifest.version == entry.version) { "Manifest version does not match repository entry" }
                require(manifest.category == entry.category) { "Manifest category does not match repository entry" }
                require(manifest.adapterId == entry.adapterId) { "Manifest adapter does not match repository entry" }
                require(entry.supported && PluginAdapterCatalog.supports(manifest.category, manifest.adapterId)) {
                    "This provider requires a newer BeatRaxus build with its reviewed adapter."
                }
                require(manifest.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                    "This plugin requires a newer BeatRaxus version"
                }

                val existing = _installed.value.firstOrNull { it.id == manifest.id }
                val addonExistedBefore = AddonManager.findById(manifest.id) != null
                val installed = InstalledPlugin(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    description = entry.description,
                    category = manifest.category,
                    adapterId = manifest.adapterId,
                    repositoryId = entry.repositoryId,
                    manifestJson = manifest.rawJson,
                    enabled = existing?.enabled ?: true
                )

                // Persist the validated declarative manifest first. If adapter activation fails,
                // remove this new record so the UI never reports a half-installed provider.
                persistInstalled(installed)
                try {
                    // Music/video sources reuse the existing, reviewed Kotlin add-on adapters.
                    if (manifest.category == PluginCategory.MUSIC_SOURCE || manifest.category == PluginCategory.VIDEO_SOURCE) {
                        val mediaType = if (manifest.category == PluginCategory.MUSIC_SOURCE) "audio" else "video"
                        val authType = manifest.configuration.optString("authType", "none")
                        withContext(Dispatchers.Main) {
                            val addon = AddonManager.installRemoteManifest(
                                AddonManifest(
                                    id = manifest.id,
                                    displayName = manifest.name,
                                    mediaType = mediaType,
                                    protocol = manifest.adapterId,
                                    authType = authType
                                )
                            ).getOrThrow()
                            if (installed.enabled) AddonManager.addAddon(appContext, addon)
                        }
                    }
                } catch (error: Exception) {
                    if (existing == null) {
                        installedFile(manifest.id).delete()
                        if (!addonExistedBefore) withContext(Dispatchers.Main) { AddonManager.uninstallAddon(manifest.id) }
                    }
                    refreshInstalled()
                    throw error
                }
                refreshInstalled()
                installed
            }
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean): Result<Unit> = runCatching {
        ensureInitialized()
        val current = _installed.value.firstOrNull { it.id == pluginId }
            ?: throw IllegalArgumentException("Installed plugin not found")
        val updated = current.copy(enabled = enabled)
        if (current.category == PluginCategory.MUSIC_SOURCE || current.category == PluginCategory.VIDEO_SOURCE) {
            if (enabled) {
                val addon = AddonManager.findById(pluginId)
                    ?: throw IllegalStateException("The built-in provider adapter is not installed")
                AddonManager.addAddon(appContext, addon)
            } else {
                AddonManager.disableAddon(pluginId)
            }
        }
        persistInstalled(updated)
        refreshInstalled()
    }

    fun uninstallPlugin(pluginId: String): Result<Unit> = runCatching {
        ensureInitialized()
        val current = _installed.value.firstOrNull { it.id == pluginId }
            ?: throw IllegalArgumentException("Installed plugin not found")
        if (current.category == PluginCategory.MUSIC_SOURCE || current.category == PluginCategory.VIDEO_SOURCE) {
            AddonManager.uninstallAddon(pluginId)
        }
        val file = installedFile(pluginId)
        if (file.exists() && !file.delete()) throw IllegalStateException("Could not remove plugin configuration")
        refreshInstalled()
    }

    fun removeRepository(repositoryId: String) {
        ensureInitialized()
        _repositories.value = _repositories.value.filterNot { it.id == repositoryId }
        refreshCatalog()
        persistRepositories()
    }

    /** Read-only configuration hook for built-in category adapters. */
    fun enabledConfigurations(category: PluginCategory): List<JSONObject> =
        _installed.value.filter { it.enabled && it.category == category }.mapNotNull {
            runCatching { JSONObject(it.manifestJson).optJSONObject("configuration") ?: JSONObject() }.getOrNull()
        }

    private fun ensureInitialized() {
        check(initialized) { "PluginRepositoryManager.initialize(context) must be called first" }
    }

    private fun refreshCatalog() {
        _catalog.value = _repositories.value.flatMap { it.plugins }
            .distinctBy { it.id }
            .sortedWith(compareBy<PluginCatalogEntry> { it.category.wireName }.thenBy { it.name.lowercase() })
    }

    private fun refreshInstalled() {
        val dir = File(appContext.filesDir, "plugin-center/installed")
        _installed.value = (dir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList())
            .mapNotNull { file -> runCatching { PluginRepositoryCodec.parseInstalled(file.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    private fun persistRepositories() {
        val array = JSONArray()
        _repositories.value.forEach { repository ->
            array.put(JSONObject()
                .put("id", repository.id)
                .put("name", repository.name)
                .put("description", repository.description)
                .put("indexUrl", repository.indexUrl)
                .put("updatedAt", repository.updatedAtEpochMs))
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPOSITORIES, array.toString()).apply()
    }

    private fun persistInstalled(plugin: InstalledPlugin) {
        val dir = File(appContext.filesDir, "plugin-center/installed")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Could not create plugin storage")
        val target = installedFile(plugin.id)
        val temp = File(dir, "${plugin.id}.json.tmp")
        val json = JSONObject(plugin.manifestJson)
            .put("repositoryId", plugin.repositoryId)
            .put("description", plugin.description)
            .put("enabled", plugin.enabled)
        temp.writeText(json.toString())
        val backup = File(dir, "${plugin.id}.json.bak")
        if (backup.exists()) backup.delete()
        val hadPrevious = target.exists()
        if (hadPrevious && !target.renameTo(backup)) throw IllegalStateException("Could not stage previous plugin configuration")
        if (!temp.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target)
            temp.delete()
            throw IllegalStateException("Could not save plugin configuration")
        }
        if (hadPrevious) backup.delete()
    }

    private fun installedFile(id: String): File {
        require(PLUGIN_ID.matches(id)) { "Invalid plugin ID" }
        return File(File(appContext.filesDir, "plugin-center/installed"), "$id.json")
    }

    private fun loadPersistedState() {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REPOSITORIES, "[]") ?: "[]"
        val restored = mutableListOf<PluginRepository>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val indexUrl = item.getString("indexUrl")
                if (!isSafeHttpsUrl(indexUrl)) continue
                restored += PluginRepository(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    description = item.optString("description"),
                    indexUrl = indexUrl,
                    updatedAtEpochMs = item.optLong("updatedAt"),
                    plugins = emptyList()
                )
            }
        }
        _repositories.value = restored
        refreshInstalled()
        // Catalogue entries are reloaded when the user opens the Plugin Center and refreshes a repository.
        refreshCatalog()
    }

    private fun repositoryIndexCandidates(input: String): List<String> {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter a repository URL" }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS repository URLs are allowed" }
        require(uri.userInfo == null) { "Repository URLs must not contain embedded credentials" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Invalid repository URL")
        if (host == "github.com") {
            val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
            require(segments.size >= 2) { "Paste a GitHub repository URL or a direct repository.json URL" }
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            require(SEGMENT.matches(owner) && SEGMENT.matches(repo)) { "Invalid GitHub repository URL" }
            return listOf("main", "master").map { branch ->
                "https://raw.githubusercontent.com/$owner/$repo/$branch/repository.json"
            }
        }
        require(host == "raw.githubusercontent.com" || trimmed.endsWith(".json", ignoreCase = true)) {
            "Use a GitHub repository URL or a direct HTTPS JSON index URL"
        }
        return listOf(trimmed)
    }

    private fun fetchJson(url: String, maxBytes: Int): JSONObject {
        require(isSafeHttpsUrl(url)) { "Only HTTPS JSON resources are allowed" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "BeatRaxus-PluginCenter/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Repository returned HTTP $status")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IllegalArgumentException("Repository JSON is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
            !uri.host.equals("localhost", ignoreCase = true) &&
            uri.host != "127.0.0.1" && uri.host != "::1"
    }.getOrDefault(false)

    private const val PLUGIN_ID_PATTERN = "[A-Za-z0-9_]{1,64}"
    private val PLUGIN_ID = Regex(PLUGIN_ID_PATTERN)
    private val SEGMENT = Regex("[A-Za-z0-9_.-]{1,100}")
}

data class PluginRepository(
    val id: String,
    val name: String,
    val description: String,
    val indexUrl: String,
    val updatedAtEpochMs: Long,
    val plugins: List<PluginCatalogEntry>
)

data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: PluginCategory,
    val adapterId: String,
    val manifestUrl: String,
    val repositoryId: String,
    val supported: Boolean
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val category: PluginCategory,
    val adapterId: String,
    val minAppVersionCode: Int,
    val configuration: JSONObject,
    val rawJson: String
)

data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: PluginCategory,
    val adapterId: String,
    val repositoryId: String,
    val manifestJson: String,
    val enabled: Boolean
)

/** Adapter IDs are identifiers for reviewed, in-app implementations, never remote code entry points. */
object PluginAdapterCatalog {
    // Only adapters that are currently wired to AddonManager are installable in this build.
    // The remaining categories have typed contracts, but need their in-app runtime adapters first.
    private val adapters: Map<String, Set<PluginCategory>> = mapOf(
        "jamendo" to setOf(PluginCategory.MUSIC_SOURCE),
        "archive_org" to setOf(PluginCategory.MUSIC_SOURCE, PluginCategory.VIDEO_SOURCE),
        "subsonic" to setOf(PluginCategory.MUSIC_SOURCE),
        "jellyfin" to setOf(PluginCategory.VIDEO_SOURCE),
        "emby" to setOf(PluginCategory.VIDEO_SOURCE),
        "stremio" to setOf(PluginCategory.VIDEO_SOURCE),
        "ytxtube" to setOf(PluginCategory.VIDEO_SOURCE),
        "youtube_audio" to setOf(PluginCategory.MUSIC_SOURCE)
    )

    fun supports(category: PluginCategory, adapterId: String): Boolean = category in adapters[adapterId].orEmpty()
}

internal object PluginRepositoryCodec {
    private val ID = Regex("[A-Za-z0-9_]{1,64}")
    private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")

    fun parseIndex(indexUrl: String, json: JSONObject): PluginRepository {
        require(json.optInt("schemaVersion", -1) == 1) { "Unsupported repository schema version" }
        val name = json.getString("name").trim().take(80)
        require(name.isNotBlank()) { "Repository name is required" }
        val pluginsJson = json.optJSONArray("plugins") ?: JSONArray()
        require(pluginsJson.length() <= 100) { "Repository contains too many plugins" }
        val repositoryId = sha256(indexUrl).take(24)
        val entries = ArrayList<PluginCatalogEntry>(pluginsJson.length())
        for (i in 0 until pluginsJson.length()) {
            val item = pluginsJson.getJSONObject(i)
            val id = item.getString("id")
            val version = item.getString("version")
            val category = PluginCategory.fromWireName(item.getString("category"))
                ?: throw IllegalArgumentException("Unknown plugin category: ${item.optString("category")}")
            val adapterId = item.getString("adapterId")
            val manifestUrl = resolveHttps(indexUrl, item.getString("manifestUrl"))
            require(ID.matches(id)) { "Invalid plugin ID: $id" }
            require(VERSION.matches(version)) { "Invalid plugin version: $version" }
            require(ID.matches(adapterId)) { "Invalid adapter ID" }
            entries += PluginCatalogEntry(
                id = id,
                name = item.getString("name").trim().take(80).also { require(it.isNotBlank()) },
                version = version,
                description = item.optString("description").take(300),
                category = category,
                adapterId = adapterId,
                manifestUrl = manifestUrl,
                repositoryId = repositoryId,
                supported = PluginAdapterCatalog.supports(category, adapterId)
            )
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate plugin IDs in repository" }
        return PluginRepository(
            id = repositoryId,
            name = name,
            description = json.optString("description").take(300),
            indexUrl = indexUrl,
            updatedAtEpochMs = System.currentTimeMillis(),
            plugins = entries
        )
    }

    fun parseManifest(json: JSONObject): PluginManifest {
        require(json.optInt("schemaVersion", -1) == 1) { "Unsupported plugin manifest schema version" }
        val id = json.getString("id")
        val name = json.getString("name").trim().take(80)
        val version = json.getString("version")
        val category = PluginCategory.fromWireName(json.getString("category"))
            ?: throw IllegalArgumentException("Unknown plugin category")
        val adapterId = json.getString("adapterId")
        val minAppVersionCode = json.optInt("minAppVersionCode", 0)
        val configuration = json.optJSONObject("configuration") ?: JSONObject()
        require(ID.matches(id)) { "Invalid plugin ID" }
        require(name.isNotBlank()) { "Plugin name is required" }
        require(VERSION.matches(version)) { "Invalid plugin version" }
        require(ID.matches(adapterId)) { "Invalid adapter ID" }
        require(minAppVersionCode >= 0) { "Invalid minimum app version" }
        require(configuration.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Plugin configuration is too large" }
        return PluginManifest(id, name, version, category, adapterId, minAppVersionCode, configuration, json.toString())
    }

    fun parseInstalled(raw: String): InstalledPlugin {
        val json = JSONObject(raw)
        val category = PluginCategory.fromWireName(json.getString("category"))
            ?: throw IllegalArgumentException("Unknown installed plugin category")
        return InstalledPlugin(
            id = json.getString("id"),
            name = json.getString("name"),
            version = json.getString("version"),
            description = json.optString("description"),
            category = category,
            adapterId = json.getString("adapterId"),
            repositoryId = json.optString("repositoryId"),
            manifestJson = raw,
            enabled = json.optBoolean("enabled", true)
        )
    }

    private fun resolveHttps(baseUrl: String, relativeOrAbsolute: String): String {
        val resolved = URI(baseUrl).resolve(relativeOrAbsolute).toString()
        val uri = URI(resolved)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Plugin manifest URLs must use HTTPS"
        }
        return resolved
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
