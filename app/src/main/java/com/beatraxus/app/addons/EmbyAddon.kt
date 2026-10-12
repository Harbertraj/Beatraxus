package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Movies, shows and music from an Emby server. Only the access token is stored, never the password. */
class EmbyAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Movies, shows & music from your Emby server"
    override val brandColor: Color = Color(0xFF52B54B)
    override val icon: ImageVector = Icons.Default.Movie
    override val capability: AddonCapability = AddonCapability.CONTROL

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    @Volatile private var currentToken: AuthToken? = null
    private var appContext: Context? = null

    override fun connect(context: Context) {
        appContext = context.applicationContext
        if (currentToken != null) return
        val saved = AddonCredentialStore.load(context, id) ?: return
        val url = saved["serverUrl"].orEmpty()
        val token = saved["token"].orEmpty()
        val userId = saved["userId"].orEmpty()
        if (url.isBlank() || token.isBlank() || userId.isBlank()) return
        currentToken = AuthToken(token, url, userId)
        _connectionState.value = AddonConnectionState.CONNECTED
    }

    override fun disconnect() {
        appContext?.let { AddonCredentialStore.clear(it, id) }
        currentToken = null
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String =
        if (_connectionState.value == AddonConnectionState.CONNECTED) "OPEN" else "SIGN IN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> =
        withContext(Dispatchers.IO) {
            try {
                val base = EmbyApi.normalizeUrl(serverUrl)
                if (base.isBlank()) return@withContext Result.failure(IOException("Enter your server address"))
                if (username.isBlank()) return@withContext Result.failure(IOException("Enter your username"))

                _connectionState.value = AddonConnectionState.CONNECTING
                val body = JSONObject().put("Username", username.trim()).put("Pw", password)
                val raw = AddonHttp.postJson(
                    "$base/Users/AuthenticateByName",
                    body,
                    mapOf("X-Emby-Authorization" to EmbyApi.AUTH_HEADER)
                )
                val json = JSONObject(raw)
                val token = json.getString("AccessToken")
                val userId = json.getJSONObject("User").getString("Id")

                val auth = AuthToken(token, base, userId)
                currentToken = auth
                appContext?.let {
                    AddonCredentialStore.save(it, id, mapOf("serverUrl" to base, "token" to token, "userId" to userId))
                }
                _connectionState.value = AddonConnectionState.CONNECTED
                Result.success(auth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value =
                    if (currentToken != null) AddonConnectionState.CONNECTED else AddonConnectionState.NOT_CONNECTED
                Result.failure(IOException(AddonHttp.friendlyMessage(e)))
            }
        }

    private fun requireToken(): AuthToken = currentToken ?: throw IOException("Not signed in to $displayName")

    private fun fetchItems(query: String?): List<AddonMediaItem> {
        val t = requireToken()
        val url = EmbyApi.itemsUrl(t.serverUrl, t.userId.orEmpty(), query)
        val raw = AddonHttp.getText(url, mapOf("X-Emby-Token" to t.token))
        return EmbyApi.parseItems(JSONObject(raw), t.serverUrl)
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> =
        withContext(Dispatchers.IO) { fetchItems(null) }

    override suspend fun search(query: String): List<AddonMediaItem> =
        withContext(Dispatchers.IO) { fetchItems(query) }

    override suspend fun streamUrl(item: AddonMediaItem): String {
        val t = requireToken()
        return EmbyApi.streamUrl(t.serverUrl, t.token, item.id, item.mediaType)
    }
}
