package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.io.IOException

/**
 * YouTube audio source (protocol "youtube_audio"), the native counterpart of the Spotube
 * "YouTube Audio" plugin (`matches` -> search, `streams` -> best audio stream).
 *
 * Compiled-in adapter: the plugin repository only supplies a declarative manifest, no code.
 * Searches YouTube Music "songs" and resolves the highest-bitrate audio-only stream via NewPipeExtractor.
 * Nothing is downloaded or stored. Use is subject to YouTube's Terms of Service.
 */
class YouTubeAudioAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {

    override val description = "Search YouTube Music songs and stream audio only"
    override val brandColor = Color(0xFFFF0000)
    override val icon: ImageVector = Icons.Default.MusicNote
    override val capability = AddonCapability.CONTROL

    private val _state = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _state.asStateFlow()

    override fun connect(context: Context) {
        ensureInit()
        _state.value = AddonConnectionState.CONNECTED
    }

    override fun disconnect() {
        _state.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}
    override fun buttonLabel() = "OPEN"

    /** No account needed. */
    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        ensureInit()
        _state.value = AddonConnectionState.CONNECTED
        return Result.success(AuthToken(token = "", serverUrl = ""))
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> = emptyList()

    override suspend fun search(query: String): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureInit()
        val service = ServiceList.YouTube
        val extractor = service.getSearchExtractor(
            service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        )
        try {
            extractor.fetchPage()
        } catch (e: Exception) {
            throw IOException("Can't reach YouTube: ${e.message}", e)
        }
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map { item ->
            AddonMediaItem(
                id = item.url,
                title = item.name,
                subtitle = item.uploaderName,
                artworkUrl = item.thumbnails.firstOrNull()?.url,
                mediaType = "audio",
                durationMs = item.duration.coerceAtLeast(0L) * 1000L,
                directPlayUrl = "",
                artist = item.uploaderName,
                pageUrl = item.url
            )
        }
    }

    override suspend fun streamUrl(item: AddonMediaItem): String = withContext(Dispatchers.IO) {
        ensureInit()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, item.id)
        } catch (e: Exception) {
            throw IOException("Could not load this track: ${e.message}", e)
        }
        info.audioStreams
            .filter { it.isUrl && it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content
            ?: throw IOException("No playable audio stream found for this track")
    }

    private companion object {
        @Volatile private var initialized = false

        @Synchronized
        fun ensureInit() {
            if (initialized) return
            NewPipe.init(NewPipeDownloader(), Localization.DEFAULT)
            initialized = true
        }
    }
}
