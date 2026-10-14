package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException

/**
 * Internet Archive: public-domain films, concerts and music through the official public APIs.
 *
 * Videos play as one file. Audio uploads are usually whole albums or concerts, so they expand into a real
 * track list (titles, durations, track order) and play as a queue with next/previous.
 */
class ArchiveOrgAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon, PagedAddon, FilterableAddon {
    override val description: String = "Internet Archive"
    override val brandColor: Color = Color(0xFF8E8E93)
    override val icon: ImageVector = Icons.Default.Archive
    override val capability: AddonCapability = AddonCapability.CONTROL

    @Volatile private var type = ArchiveOrgApi.TYPE_ALL
    @Volatile private var sort = ArchiveOrgApi.SORT_POPULAR
    @Volatile private var audioQuality = ArchiveOrgApi.QUALITY_STANDARD

    override val filterGroups: List<AddonFilterGroup> = listOf(
        AddonFilterGroup(
            id = "type", label = "SHOW", defaultKey = ArchiveOrgApi.TYPE_ALL,
            options = listOf(
                AddonFilterOption(ArchiveOrgApi.TYPE_ALL, "All"),
                AddonFilterOption(ArchiveOrgApi.TYPE_VIDEO, "Videos"),
                AddonFilterOption(ArchiveOrgApi.TYPE_AUDIO, "Audio")
            )
        ),
        AddonFilterGroup(
            id = "sort", label = "SORT", defaultKey = ArchiveOrgApi.SORT_POPULAR,
            options = listOf(
                AddonFilterOption(ArchiveOrgApi.SORT_POPULAR, "Popular"),
                AddonFilterOption(ArchiveOrgApi.SORT_TRENDING, "Trending"),
                AddonFilterOption(ArchiveOrgApi.SORT_NEWEST, "Newest")
            )
        ),
        AddonFilterGroup(
            id = "audioq", label = "AUDIO", defaultKey = ArchiveOrgApi.QUALITY_STANDARD,
            options = listOf(
                AddonFilterOption(ArchiveOrgApi.QUALITY_STANDARD, "MP3 / OGG"),
                AddonFilterOption(ArchiveOrgApi.QUALITY_LOSSLESS, "Lossless")
            )
        )
    )

    override fun setFilter(groupId: String, optionKey: String) {
        val group = filterGroups.firstOrNull { it.id == groupId } ?: return
        if (group.options.none { it.key == optionKey }) return
        when (groupId) {
            "type" -> type = optionKey
            "sort" -> sort = optionKey
            "audioq" -> audioQuality = optionKey
        }
    }

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    override fun connect(context: Context) {
        // No account needed — ready as soon as it is added.
        _connectionState.value = AddonConnectionState.CONNECTED
    }

    override fun disconnect() {
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String = "OPEN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        _connectionState.value = AddonConnectionState.CONNECTED
        return Result.success(AuthToken("", "https://archive.org"))
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> =
        if (path == null) {
            page(null, 0).items
        } else {
            listOf(
                AddonMediaItem(
                    id = path, title = "Selected Item", subtitle = null,
                    artworkUrl = ArchiveOrgApi.artworkUrl(path),
                    mediaType = "audio", durationMs = 0L, directPlayUrl = ""
                )
            )
        }

    override suspend fun search(query: String): List<AddonMediaItem> = page(query, 0).items

    /**
     * advancedsearch.php. Errors are thrown (not swallowed) so the screen can show a real
     * "can't reach archive.org" message with a RETRY button instead of an empty list.
     */
    override suspend fun page(query: String?, page: Int): AddonPage {
        val raw = AddonHttp.getTextCancellable(ArchiveOrgApi.searchUrl(query.orEmpty(), type, sort, page))
        val parsed = ArchiveOrgApi.parseSearch(raw)
        return AddonPage(parsed.items, ArchiveOrgApi.hasMore(page, parsed.numFound))
    }

    private suspend fun metadata(itemId: String): JSONObject {
        val raw = AddonHttp.getTextCancellable(ArchiveOrgApi.metadataUrl(itemId))
        val meta = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IOException("Internet Archive sent a response Beatraxus couldn't read")
        }
        ArchiveOrgApi.requirePlayable(meta)
        return meta
    }

    /** Audio uploads become a track list (queue); videos and anything without audio files play as a single item. */
    override suspend fun tracks(item: AddonMediaItem): List<AddonMediaItem> {
        if (item.mediaType != "audio" || item.directPlayUrl.isNotBlank()) return emptyList()
        return ArchiveOrgApi.audioTracks(metadata(item.id), item.id, item.title, audioQuality)
    }

    override suspend fun streamUrl(item: AddonMediaItem): String {
        // Track entries produced by tracks() already carry their final URL.
        if (item.directPlayUrl.isNotBlank()) return item.directPlayUrl
        val meta = metadata(item.id)
        val file = ArchiveOrgApi.pickStreamFile(meta, wantVideo = item.mediaType == "video", quality = audioQuality)
            ?: throw IOException("No playable audio or video file found for this item")
        return ArchiveOrgApi.downloadUrl(item.id, file)
    }
}
