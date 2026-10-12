package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Music add-on for any Subsonic-API server (Navidrome, Airsonic-Advanced, Gonic, Subsonic …).
 * Uses salted-token auth, so the account password is never sent or stored — only
 * md5(password + salt) and the salt, kept in encrypted storage.
 */
class SubsonicAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Navidrome, Subsonic, Airsonic & other Subsonic-API servers"
    override val brandColor: Color = Color(0xFF38B6FF)
    override val icon: ImageVector = Icons.Default.LibraryMusic
    override val capability: AddonCapability = AddonCapability.CONTROL

    private data class Session(val serverUrl: String, val username: String, val salt: String, val token: String)

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    @Volatile private var session: Session? = null
    private var appContext: Context? = null

    override fun connect(context: Context) {
        appContext = context.applicationContext
        if (session != null) return
        val saved = AddonCredentialStore.load(context, id) ?: return
        val url = saved["serverUrl"].orEmpty()
        val user = saved["username"].orEmpty()
        val salt = saved["salt"].orEmpty()
        val token = saved["token"].orEmpty()
        if (url.isBlank() || user.isBlank() || salt.isBlank() || token.isBlank()) return
        // No network call here; a stale session surfaces as an error when browsing.
        session = Session(url, user, salt, token)
        _connectionState.value = AddonConnectionState.CONNECTED
    }

    override fun disconnect() {
        appContext?.let { AddonCredentialStore.clear(it, id) }
        session = null
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String =
        if (_connectionState.value == AddonConnectionState.CONNECTED) "OPEN" else "SIGN IN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> =
        withContext(Dispatchers.IO) {
            try {
                val base = SubsonicApi.normalizeUrl(serverUrl)
                if (base.isBlank()) return@withContext Result.failure(IOException("Enter your server address"))
                if (username.isBlank()) return@withContext Result.failure(IOException("Enter your username"))

                _connectionState.value = AddonConnectionState.CONNECTING
                val (salt, token) = SubsonicApi.makeCredentials(password)
                val candidate = Session(base, username.trim(), salt, token)
                SubsonicApi.checkResponse(AddonHttp.getText(urlFor(candidate, "ping")))

                session = candidate
                appContext?.let {
                    AddonCredentialStore.save(
                        it, id,
                        mapOf("serverUrl" to base, "username" to candidate.username, "salt" to salt, "token" to token)
                    )
                }
                _connectionState.value = AddonConnectionState.CONNECTED
                Result.success(AuthToken(token, base, candidate.username))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value =
                    if (session != null) AddonConnectionState.CONNECTED else AddonConnectionState.NOT_CONNECTED
                Result.failure(IOException(AddonHttp.friendlyMessage(e)))
            }
        }

    private fun urlFor(s: Session, endpoint: String, extra: Map<String, String> = emptyMap()) =
        SubsonicApi.buildUrl(s.serverUrl, endpoint, s.username, s.salt, s.token, extra)

    private fun requireSession(): Session = session ?: throw IOException("Not signed in to $displayName")

    private fun coverUrl(s: Session): (String) -> String = { coverId ->
        urlFor(s, "getCoverArt", mapOf("id" to coverId, "size" to "400"))
    }

    /** Home grid: a random mix from the library. */
    override suspend fun browse(path: String?): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        val s = requireSession()
        val resp = SubsonicApi.checkResponse(AddonHttp.getText(urlFor(s, "getRandomSongs", mapOf("size" to "60"))))
        SubsonicApi.parseSongs(resp.optJSONObject("randomSongs"), coverUrl(s))
    }

    override suspend fun search(query: String): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext browse(null)
        val s = requireSession()
        val extra = mapOf("query" to query.trim(), "songCount" to "60", "albumCount" to "0", "artistCount" to "0")
        val resp = SubsonicApi.checkResponse(AddonHttp.getText(urlFor(s, "search3", extra)))
        SubsonicApi.parseSongs(resp.optJSONObject("searchResult3"), coverUrl(s))
    }

    /** format=raw asks the server for the original file (no transcoding) — what an audiophile player wants. */
    override suspend fun streamUrl(item: AddonMediaItem): String {
        val s = requireSession()
        return urlFor(s, "stream", mapOf("id" to item.id, "format" to "raw"))
    }
}
