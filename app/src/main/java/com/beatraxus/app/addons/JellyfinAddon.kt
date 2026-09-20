package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class JellyfinAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Jellyfin Media Server"
    override val brandColor: Color = Color(0xFF00A4DC)
    override val icon: ImageVector = Icons.Default.Cloud
    override val capability: AddonCapability = AddonCapability.CONTROL

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    private var currentToken: AuthToken? = null

    override fun connect(context: Context) {
        // Auth state is managed via login
    }

    override fun disconnect() {
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
        currentToken = null
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String = "OPEN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        return withContext(Dispatchers.IO) {
            try {
                val formattedUrl = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
                val url = URL("$formattedUrl/Users/AuthenticateByName")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty(
                    "X-Emby-Authorization",
                    "MediaBrowser Client=\"Beatraxus\", Device=\"Android\", DeviceId=\"beatraxus-app\", Version=\"1.0.0\""
                )
                
                val body = JSONObject().apply {
                    put("Username", username)
                    put("Pw", password)
                }
                
                connection.outputStream.write(body.toString().toByteArray())
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val token = json.getString("AccessToken")
                    val userId = json.getJSONObject("User").getString("Id")
                    
                    val authToken = AuthToken(token, formattedUrl, userId)
                    currentToken = authToken
                    _connectionState.value = AddonConnectionState.CONNECTED
                    Result.success(authToken)
                } else {
                    Result.failure(Exception("Login failed: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        search("")
    }

    override suspend fun search(query: String): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        try {
            val token = currentToken ?: return@withContext emptyList()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "${token.serverUrl}/Users/${token.userId}/Items?searchTerm=$encodedQuery&IncludeItemTypes=Movie,Episode,Audio&Recursive=true"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Emby-Token", token.token)
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val items = json.getJSONArray("Items")
                
                val results = mutableListOf<AddonMediaItem>()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val id = item.getString("Id")
                    val title = item.optString("Name", "Unknown")
                    val type = item.getString("Type")
                    val mediaType = if (type == "Audio") "audio" else "video"
                    
                    results.add(
                        AddonMediaItem(
                            id = id,
                            title = title,
                            subtitle = type,
                            artworkUrl = "${token.serverUrl}/Items/$id/Images/Primary?maxHeight=400&maxWidth=400",
                            mediaType = mediaType,
                            durationMs = item.optLong("RunTimeTicks", 0L) / 10000L,
                            directPlayUrl = ""
                        )
                    )
                }
                results
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun streamUrl(item: AddonMediaItem): String {
        val token = currentToken ?: throw Exception("Not logged in")
        val endpoint = if (item.mediaType == "audio") "Audio" else "Videos"
        return "${token.serverUrl}/$endpoint/${item.id}/stream?api_key=${token.token}"
    }
}
