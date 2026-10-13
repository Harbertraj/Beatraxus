package com.beatraxus.app.addons

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

/** Internet Archive: public-domain films, concerts and music through the official public APIs. */
class ArchiveOrgAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Internet Archive"
    override val brandColor: Color = Color(0xFF8E8E93)
    override val icon: ImageVector = Icons.Default.Archive
    override val capability: AddonCapability = AddonCapability.CONTROL

    /** "all", "video" or "audio" — set by the filter chips on the browse screen. */
    @Volatile var mediaFilter: String = "all"

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

    override suspend fun browse(path: String?): List<AddonMediaItem> = withContext(Dispatchers.IO) {
        if (path == null) {
            fetch("")
        } else {
            listOf(
                AddonMediaItem(
                    id = path, title = "Selected Item", subtitle = null,
                    artworkUrl = "https://archive.org/services/img/$path",
                    mediaType = "audio", durationMs = 0L, directPlayUrl = ""
                )
            )
        }
    }

    override suspend fun search(query: String): List<AddonMediaItem> = withContext(Dispatchers.IO) { fetch(query) }

    /**
     * advancedsearch.php. Errors are thrown (not swallowed) so the screen can show a real
     * "can't reach archive.org" message with a RETRY button instead of an empty list.
     */
    private fun fetch(text: String): List<AddonMediaItem> {
        val types = when (mediaFilter) {
            "video" -> "movies"
            "audio" -> "audio"
            else -> "movies OR audio"
        }
        val clean = text.replace(Regex("""[+\-&|!(){}\[\]^"~*?:\\/]"""), " ").trim()
        // Skip borrow-only / restricted items (TV news recordings etc.): they have no downloadable file.
        val base = "mediatype:($types) AND -access-restricted-item:true AND -collection:(tvnews OR tvarchive)"
        val q = if (clean.isBlank()) {
            "$base AND collection:(feature_films OR prelinger OR classic_tv OR opensource_movies OR " +
                "opensource_audio OR etree OR netlabels OR audio_music OR oldtimeradio)"
        } else {
            "($clean) AND $base"
        }
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "mediatype")
            .addQueryParameter("fl[]", "year")
            .addQueryParameter("fl[]", "creator")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", "60")
            .addQueryParameter("output", "json")
            .build().toString()

        val docs = JSONObject(AddonHttp.getText(url)).optJSONObject("response")?.optJSONArray("docs")
            ?: return emptyList()
        val results = ArrayList<AddonMediaItem>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val identifier = doc.optString("identifier", "")
            if (identifier.isBlank()) continue
            val year = doc.optString("year", "")
            val creator = doc.optJSONArray("creator")?.optString(0) ?: doc.optString("creator", "")
            results.add(
                AddonMediaItem(
                    id = identifier,
                    title = doc.optString("title", "").ifBlank { identifier },
                    subtitle = listOf(creator, year).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    artworkUrl = "https://archive.org/services/img/$identifier",
                    mediaType = if (doc.optString("mediatype") == "movies") "video" else "audio",
                    durationMs = 0L,
                    directPlayUrl = ""
                )
            )
        }
        return results
    }

    private val videoExt = listOf("mp4", "m4v", "webm", "mkv", "mov", "mpeg4", "mpg", "avi", "ogv")
    private val audioExt = listOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")

    override suspend fun streamUrl(item: AddonMediaItem): String = withContext(Dispatchers.IO) {
        val meta = JSONObject(AddonHttp.getText("https://archive.org/metadata/${Uri.encode(item.id)}"))
        val files = meta.optJSONArray("files")
        if (files == null || files.length() == 0) {
            throw IOException("This item has no downloadable files (it may be restricted or borrow-only)")
        }

        // Prefer the requested kind, but fall back to the other one: Archive "movies" items are
        // sometimes audio-only and vice versa.
        val order = if (item.mediaType == "video") listOf(videoExt, audioExt) else listOf(audioExt, videoExt)
        for (exts in order) {
            var best: String? = null
            var bestScore = Long.MIN_VALUE
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val name = f.optString("name")
                val lower = name.lowercase()
                val ext = lower.substringAfterLast('.', "")
                val rank = exts.indexOf(ext)
                if (rank < 0 || f.optString("private") == "true") continue
                if (lower.contains("_thumb") || lower.contains("sample") || lower.contains("__ia_thumb")) continue
                // Format order wins; then derivatives (streamable web copies) over huge originals;
                // then the larger file (full track/feature rather than a trailer).
                val derivative = if (f.optString("source") == "derivative") 1L else 0L
                val size = f.optString("size").toLongOrNull() ?: 0L
                val score = (exts.size - rank) * 1_000_000_000_000L + derivative * 100_000_000_000L + minOf(size, 99_999_999_999L)
                if (score > bestScore) { bestScore = score; best = name }
            }
            if (best != null) {
                return@withContext "https://archive.org/download/${Uri.encode(item.id)}/${Uri.encode(best, "/")}"
            }
        }
        throw IOException("No playable audio or video file found for this item")
    }
}
