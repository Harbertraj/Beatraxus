package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
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

class ArchiveOrgAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Internet Archive"
    override val brandColor: Color = Color(0xFF333333)
    override val icon: ImageVector = Icons.Default.Archive
    override val capability: AddonCapability = AddonCapability.CONTROL

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    override fun connect(context: Context) {
        // Handled by sign in / init
    }

    override fun disconnect() {
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String = "OPEN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        val token = AuthToken("", "https://archive.org")
        _connectionState.value = AddonConnectionState.CONNECTED
        return Result.success(token)
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        if (path == null) {
            search("")
        } else {
            listOf(
                AddonMediaItem(
                    id = path,
                    title = "Selected Item",
                    subtitle = null,
                    artworkUrl = "https://archive.org/services/img/$path",
                    mediaType = "audio",
                    durationMs = 0L,
                    directPlayUrl = ""
                )
            )
        }
    }

    override suspend fun search(query: String): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        try {
            val q = if (query.isBlank()) "mediatype:(movies OR audio)" else "${URLEncoder.encode(query, "UTF-8")} AND mediatype:(movies OR audio)"
            val urlString = "https://archive.org/advancedsearch.php?q=$q&fl[]=identifier&fl[]=title&fl[]=mediatype&fl[]=year&output=json&rows=50"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseString)
                val docs = json.getJSONObject("response").getJSONArray("docs")
                
                val results = mutableListOf<AddonMediaItem>()
                for (i in 0 until docs.length()) {
                    val doc = docs.getJSONObject(i)
                    val identifier = doc.optString("identifier", "")
                    if (identifier.isBlank()) continue
                    
                    val title = doc.optString("title", "Unknown Title")
                    val year = doc.optString("year", "")
                    val mediatype = doc.optString("mediatype", "audio")
                    val type = if (mediatype == "movies") "video" else "audio"
                    
                    results.add(
                        AddonMediaItem(
                            id = identifier,
                            title = title,
                            subtitle = year.takeIf { it.isNotBlank() },
                            artworkUrl = "https://archive.org/services/img/$identifier",
                            mediaType = type,
                            durationMs = 0L,
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

    override suspend fun streamUrl(item: AddonMediaItem): String = withContext(Dispatchers.IO) {
        val url = URL("https://archive.org/metadata/${item.id}")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        if (connection.responseCode == 200) {
            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseString)
            val files = json.optJSONArray("files") ?: throw Exception("No files found")
            
            var chosenFile = ""
            var maxSize = 0L

            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val name = file.optString("name", "")
                val size = file.optLong("size", 0L)
                val lowerName = name.lowercase()
                
                val isMatch = if (item.mediaType == "video") {
                    lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")
                } else {
                    lowerName.endsWith(".mp3") || lowerName.endsWith(".flac")
                }

                if (isMatch) {
                    if (size > maxSize) {
                        maxSize = size
                        chosenFile = name
                    }
                }
            }

            if (chosenFile.isNotBlank()) {
                "https://archive.org/download/${item.id}/$chosenFile"
            } else {
                throw Exception("No playable stream found for this item")
            }
        } else {
            throw Exception("Metadata fetch failed: ${connection.responseCode}")
        }
    }
}