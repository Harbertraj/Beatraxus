package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.beatraxus.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Jamendo Music (protocol "jamendo"): Creative-Commons music from independent artists through Jamendo's
 * official API v3.0. Search by title, by artist, or free text; sort; choose the stream format; page through results.
 *
 * Needs a Jamendo client ID (free, from devportal.jamendo.com). It is taken from the user's own entry
 * (kept in encrypted storage) or, if the app was built with one, from `JAMENDO_CLIENT_ID` in local.properties.
 * The ID is never logged and never put in an error message.
 *
 * Use is limited to what Jamendo's API terms allow: streaming through the official `audio` URL only — no
 * downloading, no offline copies — with the artist and a link back to Jamendo shown for every track.
 */
class JamendoAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon, PagedAddon, FilterableAddon {

    override val description: String = "Free music from independent artists — search by song or artist"
    override val brandColor: Color = Color(0xFFE5484D)
    override val icon: ImageVector = Icons.Default.MusicNote
    override val capability: AddonCapability = AddonCapability.CONTROL

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    @Volatile private var clientId: String? = null
    private var appContext: Context? = null

    @Volatile private var mode = JamendoApi.MODE_ANY
    @Volatile private var sort = JamendoApi.SORT_AUTO
    @Volatile private var quality = JamendoApi.DEFAULT_AUDIO_FORMAT

    /** "Search by artist" first looks up matching artists; remember the answer so paging doesn't repeat it. */
    @Volatile private var artistLookup: Pair<String, List<String>>? = null

    override val filterGroups: List<AddonFilterGroup> = listOf(
        AddonFilterGroup(
            id = "mode", label = "SEARCH BY", defaultKey = JamendoApi.MODE_ANY,
            options = listOf(
                AddonFilterOption(JamendoApi.MODE_ANY, "Anything"),
                AddonFilterOption(JamendoApi.MODE_TITLE, "Song title"),
                AddonFilterOption(JamendoApi.MODE_ARTIST, "Artist")
            )
        ),
        AddonFilterGroup(
            id = "sort", label = "SORT", defaultKey = JamendoApi.SORT_AUTO,
            options = listOf(
                AddonFilterOption(JamendoApi.SORT_AUTO, "Top"),
                AddonFilterOption("popularity_week", "Trending"),
                AddonFilterOption("releasedate_desc", "Newest"),
                AddonFilterOption(JamendoApi.SORT_FEATURED, "Featured")
            )
        ),
        AddonFilterGroup(
            id = "quality", label = "STREAM", defaultKey = JamendoApi.DEFAULT_AUDIO_FORMAT,
            options = listOf(
                AddonFilterOption("mp31", "MP3 96k"),
                AddonFilterOption("mp32", "MP3 192k"),
                AddonFilterOption("ogg", "OGG"),
                AddonFilterOption("flac", "FLAC")
            )
        )
    )

    override fun setFilter(groupId: String, optionKey: String) {
        val group = filterGroups.firstOrNull { it.id == groupId } ?: return
        if (group.options.none { it.key == optionKey }) return
        when (groupId) {
            "mode" -> mode = optionKey
            "sort" -> sort = optionKey
            "quality" -> quality = JamendoApi.safeAudioFormat(optionKey)
        }
    }

    override fun connect(context: Context) {
        appContext = context.applicationContext
        if (clientId != null) return
        val saved = AddonCredentialStore.load(context, id)?.get("clientId").orEmpty()
        val candidate = JamendoApi.cleanClientId(saved.ifBlank { BuildConfig.JAMENDO_CLIENT_ID })
        if (candidate.isBlank()) return // the card shows "Sign-in needed" and asks for the ID
        clientId = candidate
        _connectionState.value = AddonConnectionState.CONNECTED
    }

    override fun disconnect() {
        appContext?.let { AddonCredentialStore.clear(it, id) }
        clientId = null
        artistLookup = null
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String =
        if (_connectionState.value == AddonConnectionState.CONNECTED) "OPEN" else "SIGN IN"

    /** For this add-on the "password" slot carries the Jamendo client ID (see AddonSignInScreen). */
    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        val candidate = JamendoApi.cleanClientId(password)
        if (candidate.isBlank()) return Result.failure(IOException("Enter your Jamendo client ID"))
        _connectionState.value = AddonConnectionState.CONNECTING
        return try {
            JamendoApi.parseResults(fetch(JamendoApi.probeUrl(candidate)))
            clientId = candidate
            appContext?.let { AddonCredentialStore.save(it, id, mapOf("clientId" to candidate)) }
            _connectionState.value = AddonConnectionState.CONNECTED
            Result.success(AuthToken("", JamendoApi.BASE_URL))
        } catch (e: CancellationException) {
            _connectionState.value = stateWithoutLogin()
            throw e
        } catch (e: Exception) {
            _connectionState.value = stateWithoutLogin()
            Result.failure(IOException(AddonHttp.friendlyMessage(e)))
        }
    }

    private fun stateWithoutLogin() =
        if (clientId != null) AddonConnectionState.CONNECTED else AddonConnectionState.NOT_CONNECTED

    private fun requireClientId(): String =
        clientId ?: throw IOException("Enter your Jamendo client ID to use $displayName")

    /** One HTTP call; turns HTTP-level failures into messages that talk about Jamendo (not "wrong password"). */
    private suspend fun fetch(url: String): String = try {
        AddonHttp.getTextCancellable(url)
    } catch (e: AddonHttp.HttpStatusException) {
        throw JamendoApi.JamendoException(-1, JamendoApi.httpMessage(e.code))
    }

    override suspend fun page(query: String?, page: Int): AddonPage {
        val key = requireClientId()
        val q = query?.trim().orEmpty()
        val artistIds = if (q.isNotEmpty() && mode == JamendoApi.MODE_ARTIST) {
            val cached = artistLookup
            if (cached != null && cached.first == q) {
                cached.second
            } else {
                val ids = JamendoApi.parseArtists(fetch(JamendoApi.artistsUrl(key, q))).map { it.id }
                artistLookup = q to ids
                ids
            }
        } else emptyList()
        if (q.isNotEmpty() && mode == JamendoApi.MODE_ARTIST && artistIds.isEmpty()) {
            return AddonPage(emptyList(), false) // no artist by that name: an honest "nothing found"
        }

        val raw = fetch(
            JamendoApi.tracksUrl(
                clientId = key, query = q, mode = mode, sort = sort,
                offset = page * JamendoApi.PAGE_SIZE, audioFormat = quality, artistIds = artistIds
            )
        )
        val parsed = JamendoApi.parseTracks(raw)
        return AddonPage(parsed.items, JamendoApi.hasMore(parsed.rawCount))
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> = page(null, 0).items

    override suspend fun search(query: String): List<AddonMediaItem> = page(query, 0).items

    /**
     * Asks Jamendo for the track again right before playing: that gives a current playback URL and tells us
     * if the track was removed or has no stream. Only a plain network failure falls back to the URL from the list.
     */
    override suspend fun streamUrl(item: AddonMediaItem): String {
        val key = requireClientId()
        return try {
            val results = JamendoApi.parseResults(fetch(JamendoApi.trackByIdUrl(key, item.id, quality)))
            val first = results.optJSONObject(0)
                ?: throw JamendoApi.JamendoException(-2, "This track is no longer available on Jamendo")
            JamendoApi.toItem(first)?.directPlayUrl
                ?: throw JamendoApi.JamendoException(-2, "Jamendo doesn't offer streaming for this track")
        } catch (e: JamendoApi.JamendoException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            item.directPlayUrl.takeIf { it.isNotBlank() } ?: throw e
        }
    }
}
