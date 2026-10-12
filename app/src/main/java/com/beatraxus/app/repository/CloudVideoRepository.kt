package com.beatraxus.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.drive.CloudVideoStreamResolver
import com.beatraxus.app.drive.CloudVideoUri
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import com.beatraxus.app.utils.HttpRangeMediaDataSource
import com.beatraxus.app.utils.RemoteMediaProbe
import com.dropbox.core.v2.files.FileMetadata
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One connected cloud account whose videos can show up in the video library. */
data class CloudVideoAccount(
    val source: VideoSource,
    /** Account email (Nextcloud: username). Stored in [Video.sourceId] of the account's videos. */
    val key: String,
    val enabled: Boolean
) {
    val id: String get() = idOf(source, key)

    companion object {
        fun idOf(source: VideoSource, key: String) = "${source.name}:$key"
    }
}

/** Lets the player tell the library the real runtime / size of a video it just opened. */
object CloudVideoMetadataBus {
    data class Update(val videoId: String, val durationMs: Long, val width: Int, val height: Int)

    val updates = MutableSharedFlow<Update>(extraBufferCapacity = 16)
}

/**
 * Lists the videos stored in the connected cloud drives (Google Drive, Dropbox, OneDrive, Box,
 * Nextcloud) and keeps a small JSON cache of them so the library can show them instantly.
 *
 * Nothing is downloaded: each video gets a `cloudvideo://` address that
 * [com.beatraxus.app.drive.CloudVideoDataSource] streams when the player opens it.
 * Thumbnails and runtimes are filled in afterwards by [enrich].
 */
class CloudVideoRepository(private val context: Context) {

    private val app: BeatraxusApplication get() = context.applicationContext as BeatraxusApplication
    private val resolver: CloudVideoStreamResolver by lazy { CloudVideoStreamResolver.get(context) }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /** Google Drive hands out a short-lived thumbnail link per file while listing; keep it for [enrich]. */
    private val driveThumbnailLinks = ConcurrentHashMap<String, String>()

    // ── Cache ───────────────────────────────────────────────────────────────────────────────

    fun loadCached(): List<Video> {
        val file = cacheFile
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                try { videoFromJson(array.getJSONObject(index)) } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read cached cloud videos: ${e.message}")
            emptyList()
        }
    }

    fun saveCached(videos: List<Video>) {
        try {
            val array = JSONArray()
            videos.filter { it.source in CLOUD_SOURCES }.forEach { array.put(videoToJson(it)) }
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save cloud videos: ${e.message}")
        }
    }

    // ── Accounts ────────────────────────────────────────────────────────────────────────────

    suspend fun connectedAccounts(): List<CloudVideoAccount> = buildList {
        app.driveAccountRepository.accounts.first().forEach { add(CloudVideoAccount(VideoSource.GDRIVE, it.email, it.enabled)) }
        app.dropboxAccountRepository.accounts.first().forEach { add(CloudVideoAccount(VideoSource.DROPBOX, it.email, it.enabled)) }
        app.onedriveAccountRepository.accounts.first().forEach { add(CloudVideoAccount(VideoSource.ONEDRIVE, it.email, it.enabled)) }
        app.boxAccountRepository.accounts.first().forEach { add(CloudVideoAccount(VideoSource.BOX, it.email, it.enabled)) }
        app.nextcloudAccountRepository.accounts.first().forEach { add(CloudVideoAccount(VideoSource.NEXTCLOUD, it.username, it.enabled)) }
    }

    // ── Scanning ────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the videos currently stored in [account], or null if they couldn't be listed (so the
     * caller keeps whatever it already has). Thumbnails and runtimes found earlier are kept.
     */
    suspend fun scan(account: CloudVideoAccount, existing: Map<String, Video>): List<Video>? =
        withContext(Dispatchers.IO) {
            try {
                val found = when (account.source) {
                    VideoSource.GDRIVE -> scanDrive(account.key)
                    VideoSource.DROPBOX -> scanDropbox(account.key)
                    VideoSource.ONEDRIVE -> scanOneDrive(account.key)
                    VideoSource.BOX -> scanBox(account.key)
                    VideoSource.NEXTCLOUD -> scanNextcloud(account.key)
                    else -> null
                } ?: return@withContext null

                found.map { video ->
                    val old = existing[video.id]
                    if (old == null) video else video.copy(
                        durationMs = if (video.durationMs > 0) video.durationMs else old.durationMs,
                        resolutionWidth = if (video.resolutionHeight > 0) video.resolutionWidth else old.resolutionWidth,
                        resolutionHeight = if (video.resolutionHeight > 0) video.resolutionHeight else old.resolutionHeight
                    )
                }.sortedByDescending { it.dateAdded }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not list ${account.source} videos of ${account.key}: ${e.message}")
                null
            }
        }

    // Google Drive ---------------------------------------------------------------------------

    private suspend fun scanDrive(email: String): List<Video>? {
        val token = app.driveAccountRepository.getAccessToken(email) ?: return null
        val auth = mapOf("Authorization" to "Bearer $token")
        val out = mutableListOf<Video>()
        var pageToken: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "mimeType contains 'video/' and trashed = false")
                .addQueryParameter(
                    "fields",
                    "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink," +
                        "videoMediaMetadata(width,height,durationMillis))"
                )
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
                .toString()
            val json = getJson(url, auth) ?: return null
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val name = f.optString("name")
                val id = f.optString("id")
                if (name.isEmpty() || id.isEmpty()) continue
                val meta = f.optJSONObject("videoMediaMetadata")
                val video = newVideo(
                    source = VideoSource.GDRIVE,
                    account = email,
                    fileId = id,
                    name = name,
                    size = f.optString("size").toLongOrNull() ?: 0L,
                    modifiedSec = isoToEpochSec(f.optString("modifiedTime")),
                    mime = f.optString("mimeType"),
                    folderSuffix = "",
                    durationMs = meta?.optString("durationMillis")?.toLongOrNull() ?: 0L,
                    width = meta?.optInt("width") ?: 0,
                    height = meta?.optInt("height") ?: 0
                )
                f.optString("thumbnailLink").takeIf { it.isNotEmpty() }?.let { driveThumbnailLinks[video.id] = it }
                out.add(video)
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    // Dropbox --------------------------------------------------------------------------------

    private suspend fun scanDropbox(email: String): List<Video>? {
        val client = app.dropboxAccountRepository.getClient(email) ?: return null
        val out = mutableListOf<Video>()
        var result = client.files().listFolderBuilder("").withRecursive(true).start()
        while (true) {
            result.entries.filterIsInstance<FileMetadata>().filter { isVideoName(it.name) }.forEach { file ->
                out.add(
                    newVideo(
                        source = VideoSource.DROPBOX,
                        account = email,
                        fileId = file.id,
                        name = file.name,
                        size = file.size,
                        modifiedSec = file.serverModified?.time?.div(1000) ?: 0L,
                        mime = "",
                        folderSuffix = file.pathDisplay?.substringBeforeLast('/', "").orEmpty()
                    )
                )
            }
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
        return out
    }

    // OneDrive -------------------------------------------------------------------------------

    private suspend fun scanOneDrive(email: String): List<Video>? {
        val token = app.onedriveAccountRepository.getAccessToken(email) ?: return null
        val auth = mapOf("Authorization" to "Bearer $token")
        val byId = LinkedHashMap<String, Video>()
        var anySearchWorked = false

        // OneDrive can't filter by type, so look for each common video extension and keep real video files.
        for (ext in ONEDRIVE_SEARCH_EXTENSIONS) {
            var url: String? = "https://graph.microsoft.com/v1.0/me/drive/root/search(q='$ext')" +
                "?\$select=id,name,size,lastModifiedDateTime,file,video&\$top=200"
            while (url != null) {
                val json = getJson(url, auth) ?: break
                anySearchWorked = true
                val items = json.optJSONArray("value") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (!item.has("file")) continue // folder
                    val name = item.optString("name")
                    val id = item.optString("id")
                    val facet = item.optJSONObject("video")
                    if (id.isEmpty() || (facet == null && !isVideoName(name))) continue
                    val video = newVideo(
                        source = VideoSource.ONEDRIVE,
                        account = email,
                        fileId = id,
                        name = name,
                        size = item.optLong("size"),
                        modifiedSec = isoToEpochSec(item.optString("lastModifiedDateTime")),
                        mime = item.optJSONObject("file")?.optString("mimeType").orEmpty(),
                        folderSuffix = "",
                        durationMs = facet?.optLong("duration") ?: 0L,
                        width = facet?.optInt("width") ?: 0,
                        height = facet?.optInt("height") ?: 0
                    )
                    byId[video.id] = video
                }
                url = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
            }
        }
        return if (anySearchWorked) byId.values.toList() else null
    }

    // Box ------------------------------------------------------------------------------------

    private class BoxFolderListing(val subfolders: List<Pair<String, String>>)

    private suspend fun scanBox(email: String): List<Video>? {
        val token = app.boxAccountRepository.getAccessToken(email) ?: return null
        val auth = mapOf("Authorization" to "Bearer $token")
        val out = java.util.Collections.synchronizedList(mutableListOf<Video>())
        val gate = Semaphore(4)

        var level: List<Pair<String, String>> = listOf("0" to "")
        var visited = 0
        var rootListed = false
        while (level.isNotEmpty() && visited < MAX_FOLDERS_PER_SCAN) {
            val batch = level.take(MAX_FOLDERS_PER_SCAN - visited)
            visited += batch.size
            val listings = coroutineScope {
                batch.map { (folderId, path) ->
                    async(Dispatchers.IO) { gate.withPermit { listBoxFolder(folderId, path, auth, email, out) } }
                }.awaitAll()
            }
            if (!rootListed) {
                if (listings.firstOrNull() == null) return null // couldn't even read the top folder
                rootListed = true
            }
            level = listings.filterNotNull().flatMap { it.subfolders }
        }
        return out.toList()
    }

    /** Lists one Box folder: adds its videos to [out] and returns its sub-folders (null on failure). */
    private fun listBoxFolder(
        folderId: String,
        path: String,
        auth: Map<String, String>,
        email: String,
        out: MutableList<Video>
    ): BoxFolderListing? {
        val subfolders = mutableListOf<Pair<String, String>>()
        var offset = 0
        while (true) {
            val url = "https://api.box.com/2.0/folders/$folderId/items?fields=id,name,size,modified_at,type&limit=1000&offset=$offset"
            val json = try {
                getJson(url, auth)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Could not list Box folder $folderId: ${e.message}")
                null
            } ?: return null
            val entries = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val name = e.optString("name")
                when (e.optString("type")) {
                    "folder" -> subfolders.add(e.optString("id") to "$path/$name")
                    "file" -> if (isVideoName(name)) {
                        out.add(
                            newVideo(
                                source = VideoSource.BOX,
                                account = email,
                                fileId = e.optString("id"),
                                name = name,
                                size = e.optLong("size"),
                                modifiedSec = isoToEpochSec(e.optString("modified_at")),
                                mime = "",
                                folderSuffix = path
                            )
                        )
                    }
                }
            }
            offset += entries.length()
            val total = json.optInt("total_count", 0)
            if (entries.length() == 0 || offset >= total) break
        }
        return BoxFolderListing(subfolders)
    }

    // Nextcloud ------------------------------------------------------------------------------

    private suspend fun scanNextcloud(username: String): List<Video>? {
        val accounts = app.nextcloudAccountRepository.accounts.first().filter { it.username == username }
        if (accounts.isEmpty()) return null
        val out = mutableListOf<Video>()
        var anyWorked = false
        for (account in accounts) {
            val listed = scanNextcloudAccount(account)
            if (listed != null) {
                anyWorked = true
                out.addAll(listed)
            }
        }
        return if (anyWorked) out else null
    }

    private suspend fun scanNextcloudAccount(account: NextcloudAccount): List<Video>? {
        val root = nextcloudRoot(account)
        val rootPath = URI(root).path.trimEnd('/')
        val sardine = OkHttpSardine().apply { setCredentials(account.username, account.appPassword) }
        val gate = Semaphore(3)
        val out = java.util.Collections.synchronizedList(mutableListOf<Video>())

        var level: List<String> = listOf(root)
        var visited = 0
        var rootListed = false
        while (level.isNotEmpty() && visited < MAX_FOLDERS_PER_SCAN) {
            val batch = level.take(MAX_FOLDERS_PER_SCAN - visited)
            visited += batch.size
            val listings = coroutineScope {
                batch.map { dirUrl ->
                    async(Dispatchers.IO) {
                        gate.withPermit { listNextcloudFolder(sardine, dirUrl, rootPath, account.username, out) }
                    }
                }.awaitAll()
            }
            if (!rootListed) {
                if (listings.firstOrNull() == null) return null
                rootListed = true
            }
            level = listings.filterNotNull().flatten()
        }
        return out.toList()
    }

    /** Lists one WebDAV folder: adds its videos to [out] and returns its sub-folder URLs (null on failure). */
    private fun listNextcloudFolder(
        sardine: OkHttpSardine,
        dirUrl: String,
        rootPath: String,
        username: String,
        out: MutableList<Video>
    ): List<String>? {
        return try {
            val dirPath = URI(dirUrl).path.trimEnd('/')
            val relative = dirPath.removePrefix(rootPath).trim('/')
            val subfolders = mutableListOf<String>()
            for (res in sardine.list(dirUrl)) {
                val absolute = URI(dirUrl).resolve(res.href).toString()
                if (URI(absolute).path.trimEnd('/') == dirPath) continue // the folder itself
                if (res.isDirectory) {
                    subfolders.add(absolute)
                } else if (isVideoName(res.name) || res.contentType?.startsWith("video/") == true) {
                    out.add(
                        newVideo(
                            source = VideoSource.NEXTCLOUD,
                            account = username,
                            fileId = absolute,
                            name = res.name,
                            size = res.contentLength ?: 0L,
                            modifiedSec = res.modified?.time?.div(1000) ?: 0L,
                            mime = res.contentType.orEmpty().takeIf { it.startsWith("video/") }.orEmpty(),
                            folderSuffix = if (relative.isEmpty()) "" else "/$relative"
                        )
                    )
                }
            }
            subfolders
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list Nextcloud folder $dirUrl: ${e.message}")
            null
        }
    }

    private fun nextcloudRoot(account: NextcloudAccount): String {
        var base = account.serverUrl.trim()
        if (!base.contains("://")) base = "https://$base"
        base = base.trimEnd('/')
        return if (base.contains("/remote.php/") || base.contains("/dav/")) "$base/"
        else "$base/remote.php/dav/files/${Uri.encode(account.username)}/"
    }

    // ── Building videos ─────────────────────────────────────────────────────────────────────

    private fun newVideo(
        source: VideoSource,
        account: String,
        fileId: String,
        name: String,
        size: Long,
        modifiedSec: Long,
        mime: String,
        folderSuffix: String,
        durationMs: Long = 0L,
        width: Int = 0,
        height: Int = 0
    ): Video {
        val id = videoId(source, fileId)
        val title = name.substringBeforeLast('.', name).trim().ifBlank { name }
        val folder = "${schemeOf(source)}://$account${if (folderSuffix.isEmpty() || folderSuffix.startsWith("/")) folderSuffix else "/$folderSuffix"}"
        val thumb = thumbnailFile(id).takeIf { it.exists() && it.length() > 0 }
        return Video(
            id = id,
            uri = CloudVideoUri.build(source, account, fileId, size),
            title = title,
            displayName = name,
            folderPath = folder,
            durationMs = durationMs,
            sizeBytes = size,
            resolutionWidth = width,
            resolutionHeight = height,
            mimeType = mime.ifBlank { mimeFromName(name) },
            dateAdded = modifiedSec,
            thumbnailUri = thumb?.let { Uri.fromFile(it) },
            source = source,
            sourceId = account
        )
    }

    // ── Thumbnails & runtime ────────────────────────────────────────────────────────────────

    /**
     * Fills in the thumbnail and runtime of [videos], newest first. Cheap provider thumbnails come
     * first; videos still without a thumbnail (or runtime) get a few MB of their data read to decode
     * a frame, capped per call so a huge library doesn't eat the data plan in one go.
     * [onUpdated] gets every video that improved (called from background threads).
     */
    suspend fun enrich(
        videos: List<Video>,
        onUpdated: (Video) -> Unit,
        allowHeavy: Boolean = true,
        isPlaybackActive: () -> Boolean = { false }
    ) {
        val todo = videos
            .filter { !hasThumbnail(it.id) || it.durationMs <= 0L || it.resolutionHeight <= 0 }
            .sortedByDescending { it.dateAdded }
        if (todo.isEmpty()) return

        val light = Semaphore(LIGHT_PARALLELISM)
        val heavy = Semaphore(HEAVY_PARALLELISM)
        val heavyBudget = AtomicInteger(MAX_HEAVY_PER_RUN)

        coroutineScope {
            todo.map { original ->
                async(Dispatchers.IO) {
                    var video = original
                    var changed = false
                    val parsed = CloudVideoUri.parse(video.uri) ?: return@async

                    // 1. The provider's own thumbnail (a few KB).
                    if (!hasThumbnail(video.id)) {
                        val bytes = light.withPermit {
                            try {
                                providerThumbnail(video, parsed)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.d(TAG, "No provider thumbnail for ${video.displayName}: ${e.message}")
                                null
                            }
                        }
                        if (bytes != null && RemoteMediaProbe.saveThumbnailBytes(bytes, thumbnailFile(video.id))) {
                            video = video.copy(thumbnailUri = Uri.fromFile(thumbnailFile(video.id)))
                            changed = true
                        }
                    }

                    // 2. Decode a frame / read the header straight from the cloud file.
                    val needFrame = !hasThumbnail(video.id)
                    val needMeta = video.durationMs <= 0L || video.resolutionHeight <= 0
                    if ((needFrame || needMeta) && allowHeavy && heavyBudget.decrementAndGet() >= 0) {
                        heavy.withPermit {
                            while (isPlaybackActive()) delay(2_000) // don't fight a playing video for the decoder
                            val resolved = try {
                                resolver.resolve(parsed)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                            if (resolved != null) {
                                val result = withContext(Dispatchers.IO) {
                                    RemoteMediaProbe.probe(
                                        HttpRangeMediaDataSource(http, resolved.url, resolved.headers, video.sizeBytes),
                                        needFrame
                                    )
                                }
                                if (result != null) {
                                    var updated = video
                                    if (needFrame && result.frame != null &&
                                        RemoteMediaProbe.saveThumbnail(result.frame, thumbnailFile(video.id))
                                    ) {
                                        updated = updated.copy(thumbnailUri = Uri.fromFile(thumbnailFile(video.id)))
                                    }
                                    if (updated.durationMs <= 0L && result.durationMs > 0L) {
                                        updated = updated.copy(durationMs = result.durationMs)
                                    }
                                    if (updated.resolutionHeight <= 0 && result.height > 0) {
                                        updated = updated.copy(resolutionWidth = result.width, resolutionHeight = result.height)
                                    }
                                    if (updated != video) {
                                        video = updated
                                        changed = true
                                    }
                                }
                            }
                        }
                    }

                    if (changed) onUpdated(video)
                }
            }.awaitAll()
        }
    }

    /** Downloads the thumbnail the provider itself offers for [video] (null when there is none). */
    private suspend fun providerThumbnail(video: Video, parsed: CloudVideoUri.Parsed): ByteArray? {
        when (parsed.source) {
            VideoSource.GDRIVE -> {
                val link = driveThumbnailLinks[video.id] ?: return null
                val token = app.driveAccountRepository.getAccessToken(parsed.account) ?: return null
                return fetchBytes(link.replace("=s220", "=s480"), mapOf("Authorization" to "Bearer $token"))
            }
            VideoSource.ONEDRIVE -> {
                val token = app.onedriveAccountRepository.getAccessToken(parsed.account) ?: return null
                val url = "https://graph.microsoft.com/v1.0/me/drive/items/${Uri.encode(parsed.fileId)}/thumbnails/0/medium/content"
                return fetchBytes(url, mapOf("Authorization" to "Bearer $token"))
            }
            VideoSource.BOX -> {
                val token = app.boxAccountRepository.getAccessToken(parsed.account) ?: return null
                val url = "https://api.box.com/2.0/files/${Uri.encode(parsed.fileId)}/thumbnail.jpg" +
                    "?min_height=160&min_width=160&max_height=320&max_width=320"
                return fetchBytes(url, mapOf("Authorization" to "Bearer $token"))
            }
            VideoSource.NEXTCLOUD -> {
                val resolved = resolver.resolve(parsed)
                val match = NEXTCLOUD_FILE_URL.matchEntire(URI(parsed.fileId).rawPath ?: return null) ?: return null
                val subPath = match.groupValues[1]
                val relative = Uri.decode(match.groupValues[2])
                val uri = URI(parsed.fileId)
                val origin = "${uri.scheme}://${uri.rawAuthority}$subPath"
                val url = "$origin/index.php/core/preview.png?file=${Uri.encode("/$relative")}&x=480&y=270&a=true"
                return fetchBytes(url, resolved.headers)
            }
            else -> return null // Dropbox has no video thumbnails: the frame-grab step covers it
        }
    }

    private fun hasThumbnail(videoId: String): Boolean =
        thumbnailFile(videoId).let { it.exists() && it.length() > 0 }

    private fun thumbnailFile(videoId: String): File = RemoteMediaProbe.thumbnailFile(context.filesDir, videoId)

    // ── HTTP helpers ────────────────────────────────────────────────────────────────────────

    private fun getJson(url: String, headers: Map<String, String>): JSONObject? {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Request failed (HTTP ${response.code}): ${url.take(90)}")
                null
            } else {
                response.body?.string()?.let { JSONObject(it) }
            }
        }
    }

    /** GET that follows redirects (OkHttp drops the auth header when the redirect leaves the host). */
    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else {
                val length = response.body?.contentLength() ?: -1L
                if (length > MAX_THUMBNAIL_BYTES) null else response.body?.bytes()
            }
        }
    }

    // ── JSON ────────────────────────────────────────────────────────────────────────────────

    private fun videoToJson(v: Video): JSONObject = JSONObject().apply {
        put("id", v.id)
        put("uri", v.uri.toString())
        put("title", v.title)
        put("displayName", v.displayName)
        put("folderPath", v.folderPath)
        put("durationMs", v.durationMs)
        put("sizeBytes", v.sizeBytes)
        put("width", v.resolutionWidth)
        put("height", v.resolutionHeight)
        put("mime", v.mimeType)
        put("dateAdded", v.dateAdded)
        put("thumb", v.thumbnailUri?.toString() ?: JSONObject.NULL)
        put("source", v.source.name)
        put("sourceId", v.sourceId ?: JSONObject.NULL)
    }

    private fun videoFromJson(o: JSONObject): Video {
        val thumb = if (o.isNull("thumb")) null else Uri.parse(o.getString("thumb")).takeIf { uri ->
            uri.path?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true
        }
        return Video(
            id = o.getString("id"),
            uri = Uri.parse(o.getString("uri")),
            title = o.getString("title"),
            displayName = o.getString("displayName"),
            folderPath = o.getString("folderPath"),
            durationMs = o.optLong("durationMs"),
            sizeBytes = o.optLong("sizeBytes"),
            resolutionWidth = o.optInt("width"),
            resolutionHeight = o.optInt("height"),
            mimeType = o.optString("mime", "video/mp4"),
            dateAdded = o.optLong("dateAdded"),
            thumbnailUri = thumb,
            source = VideoSource.valueOf(o.getString("source")),
            sourceId = if (o.isNull("sourceId")) null else o.getString("sourceId")
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

    private fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private fun isoToEpochSec(value: String): Long = try {
        if (value.isEmpty()) 0L else OffsetDateTime.parse(value).toEpochSecond()
    } catch (_: Exception) {
        0L
    }

    private fun schemeOf(source: VideoSource): String = when (source) {
        VideoSource.GDRIVE -> "gdrive"
        VideoSource.DROPBOX -> "dropbox"
        VideoSource.ONEDRIVE -> "onedrive"
        VideoSource.BOX -> "box"
        VideoSource.NEXTCLOUD -> "nextcloud"
        else -> source.name.lowercase()
    }

    private fun mimeFromName(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts", "m2ts" -> "video/mp2t"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }

    companion object {
        private const val TAG = "CloudVideoRepo"
        private const val CACHE_FILE = "cloud_videos.json"
        private const val MAX_FOLDERS_PER_SCAN = 1500
        private const val LIGHT_PARALLELISM = 4
        private const val HEAVY_PARALLELISM = 2
        private const val MAX_HEAVY_PER_RUN = 60
        private const val MAX_THUMBNAIL_BYTES = 4L * 1024 * 1024

        val CLOUD_SOURCES = setOf(
            VideoSource.GDRIVE, VideoSource.DROPBOX, VideoSource.ONEDRIVE, VideoSource.BOX, VideoSource.NEXTCLOUD
        )

        private val ONEDRIVE_SEARCH_EXTENSIONS = listOf("mp4", "mkv", "mov", "avi", "webm", "m4v")
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg"
        )

        // <optional sub-folder install>/remote.php/(dav/files/<user>|webdav)/<path of the file>
        private val NEXTCLOUD_FILE_URL = Regex("^(.*?)/remote\\.php/(?:dav/files/[^/]+|webdav)/(.*)$")

        /** Stable, file-name-safe id for a cloud file. */
        fun videoId(source: VideoSource, fileId: String): String {
            val prefix = when (source) {
                VideoSource.GDRIVE -> "gd"
                VideoSource.DROPBOX -> "db"
                VideoSource.ONEDRIVE -> "od"
                VideoSource.BOX -> "bx"
                VideoSource.NEXTCLOUD -> "nc"
                else -> source.name.lowercase()
            }
            // Nextcloud ids are whole URLs: hash them. Everything else is already short.
            val body = if (source == VideoSource.NEXTCLOUD) {
                java.security.MessageDigest.getInstance("MD5").digest(fileId.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            } else {
                fileId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            }
            return "${prefix}_video_$body"
        }
    }
}
