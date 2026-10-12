package com.beatraxus.app.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import com.beatraxus.app.model.parseTelegramChannelName
import com.beatraxus.app.telegram.TdLibManager
import com.beatraxus.app.telegram.TelegramMediaDataSource
import com.beatraxus.app.telegram.TelegramVideoUri
import com.beatraxus.app.utils.RemoteMediaProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finds the videos posted in the connected Telegram channels and keeps a small JSON cache of
 * them, so the video library can show them instantly and then refresh in the background.
 *
 * Videos are not downloaded here: each one just gets a `tgvideo://` address that
 * [com.beatraxus.app.telegram.TelegramVideoDataSource] streams when the player opens it.
 *
 * Refreshing is split in two so the library never waits on slow downloads:
 *  1. [scanChannel] lists the videos at once (with whatever thumbnail is already on disk).
 *  2. [enrich] then fetches the real thumbnails and the runtime in the background, retrying what
 *     failed, and reports every improved video through a callback.
 */
class TelegramVideoRepository(private val context: Context) {

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    // Same folder VideoThumbnailHelper uses, so the library finds these thumbnails with no extra work.
    private val thumbDir: File get() = File(context.filesDir, THUMBNAIL_DIR).apply { mkdirs() }

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
            Log.w(TAG, "Could not read cached Telegram videos: ${e.message}")
            emptyList()
        }
    }

    fun saveCached(videos: List<Video>) {
        try {
            val array = JSONArray()
            videos.filter { it.source == VideoSource.TELEGRAM }.forEach { array.put(videoToJson(it)) }
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save Telegram videos: ${e.message}")
        }
    }

    // ── Scanning ────────────────────────────────────────────────────────────────────────────

    /** The videos of one channel plus the work still to do on them (see [enrich]). */
    class ChannelScan internal constructor(
        val videos: List<Video>,
        internal val pending: List<Pending>
    )

    internal class Pending(val videoId: String, val candidate: Candidate)

    /**
     * Returns the videos currently in [channelUrl] right away (no waiting for thumbnails), or null
     * if the channel couldn't be read (so the caller keeps whatever it already has).
     */
    suspend fun scanChannel(
        tdLib: TdLibManager,
        channelUrl: String,
        existing: Map<String, Video>
    ): ChannelScan? {
        val username = parseTelegramChannelName(channelUrl)
        val sourceId = channelUrl.trim().removeSuffix("/")

        val messages = try {
            tdLib.getChannelVideoMessages(username, 500)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not read videos of $username: ${e.message}")
            return null
        }
        if (messages.isEmpty()) return null

        val pending = mutableListOf<Pending>()
        val videos = withContext(Dispatchers.IO) {
            messages.mapNotNull { msg ->
                val candidate = candidateOf(msg) ?: return@mapNotNull null
                val id = videoId(msg.chatId, msg.id)
                val old = existing[id]

                val title = candidate.fileName.substringBeforeLast('.', candidate.fileName).trim()
                    .ifBlank { candidate.caption.lineSequence().firstOrNull().orEmpty().trim().take(80) }
                    .ifBlank { "Video ${msg.id}" }
                val mime = candidate.mime.ifBlank { mimeFromName(candidate.fileName) }
                val displayName = candidate.fileName.ifBlank { "$title.${extensionFromMime(mime)}" }
                val size = candidate.file.size.toLong()

                // Runtime / resolution: Telegram only knows them for real "video" messages. Videos
                // sent as files report 0, so keep what an earlier probe found.
                val durationMs = if (candidate.durationSec > 0) candidate.durationSec * 1000L else old?.durationMs ?: 0L
                val width = if (candidate.width > 0) candidate.width else old?.resolutionWidth ?: 0
                val height = if (candidate.height > 0) candidate.height else old?.resolutionHeight ?: 0

                // Whatever thumbnail already sits on disk (real one, or the blurry placeholder).
                // A missing / placeholder-only thumbnail is repaired later by enrich().
                val thumbnail = bestThumbnailOnDisk(id) ?: writePlaceholder(id, candidate.mini)

                if (!hasRealThumbnail(id) || durationMs <= 0L || height <= 0) {
                    pending.add(Pending(id, candidate))
                }

                Video(
                    id = id,
                    uri = TelegramVideoUri.build(msg.chatId, msg.id, candidate.file.id, size),
                    title = title,
                    displayName = displayName,
                    folderPath = "telegram://$username",
                    durationMs = durationMs,
                    sizeBytes = size,
                    resolutionWidth = width,
                    resolutionHeight = height,
                    mimeType = mime,
                    dateAdded = msg.date.toLong(),
                    thumbnailUri = thumbnail,
                    source = VideoSource.TELEGRAM,
                    sourceId = sourceId
                )
            }
        }
        Log.d(TAG, "Found ${videos.size} videos in $username (${pending.size} need thumbnail/runtime)")
        return ChannelScan(videos.sortedByDescending { it.dateAdded }, pending)
    }

    /**
     * Fetches the real thumbnail and the runtime of the videos [scan] found, a few at a time.
     * [onUpdated] gets every video that improved (it is called from background threads).
     *
     * @param allowHeavy false skips the step that reads a few MB from the video itself (used when
     *   the network rules say no heavy downloads right now).
     * @param isPlaybackActive while true, work that would compete with a playing video is postponed.
     */
    suspend fun enrich(
        tdLib: TdLibManager,
        scan: ChannelScan,
        onUpdated: (Video) -> Unit,
        allowHeavy: Boolean = true,
        isPlaybackActive: () -> Boolean = { false }
    ) {
        if (scan.pending.isEmpty()) return
        val byId = scan.videos.associateBy { it.id }
        val light = Semaphore(LIGHT_PARALLELISM)
        val heavy = Semaphore(HEAVY_PARALLELISM)

        coroutineScope {
            scan.pending.map { pending ->
                async(Dispatchers.IO) {
                    var video = byId[pending.videoId] ?: return@async
                    val candidate = pending.candidate
                    var changed = false

                    // 1. The thumbnail Telegram made for the message (small download, retried).
                    if (!hasRealThumbnail(video.id)) {
                        val file = light.withPermit { downloadThumbnail(tdLib, video.id, candidate.thumbnail) }
                        if (file != null) {
                            video = video.copy(thumbnailUri = Uri.fromFile(file))
                            changed = true
                        }
                    }

                    // 2. Still no thumbnail, or the runtime is unknown: read a few MB of the video
                    //    itself and let Android decode a frame / the header.
                    val needFrame = !hasRealThumbnail(video.id)
                    val needMeta = video.durationMs <= 0L || video.resolutionHeight <= 0
                    if ((needFrame || needMeta) && allowHeavy) {
                        heavy.withPermit {
                            while (isPlaybackActive()) delay(2_000) // don't fight a playing video for the decoder
                            val fileId = candidate.file.id
                            val result = try {
                                withContext(Dispatchers.IO) {
                                    RemoteMediaProbe.probe(TelegramMediaDataSource(tdLib, fileId, video.sizeBytes), needFrame)
                                }
                            } finally {
                                // The probe only needed a few ranges: don't leave them in TDLib's cache.
                                if (!isPlaybackActive()) {
                                    try { tdLib.send(TdApi.DeleteFile(fileId)) } catch (_: Exception) {}
                                }
                            }
                            if (result != null) {
                                var updated = video
                                if (needFrame && result.frame != null &&
                                    RemoteMediaProbe.saveThumbnail(result.frame, realThumbnailFile(video.id))
                                ) {
                                    updated = updated.copy(thumbnailUri = Uri.fromFile(realThumbnailFile(video.id)))
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

                    if (changed) onUpdated(video)
                }
            }.awaitAll()
        }
    }

    internal class Candidate(
        val fileName: String,
        val mime: String,
        val durationSec: Int,
        val width: Int,
        val height: Int,
        val file: TdApi.File,
        val thumbnail: TdApi.Thumbnail?,
        val mini: ByteArray?,
        val caption: String
    )

    private fun candidateOf(msg: TdApi.Message): Candidate? = when (val content = msg.content) {
        is TdApi.MessageVideo -> {
            val v = content.video
            Candidate(
                fileName = v.fileName.orEmpty(),
                mime = v.mimeType.orEmpty(),
                durationSec = v.duration,
                width = v.width,
                height = v.height,
                file = v.video,
                thumbnail = v.thumbnail,
                mini = v.minithumbnail?.data,
                caption = content.caption?.text.orEmpty()
            )
        }
        is TdApi.MessageDocument -> {
            val d = content.document
            if (!isVideoFile(d.mimeType, d.fileName)) null else Candidate(
                fileName = d.fileName.orEmpty(),
                mime = d.mimeType.orEmpty(),
                durationSec = 0,
                width = 0,
                height = 0,
                file = d.document,
                thumbnail = d.thumbnail,
                mini = d.minithumbnail?.data,
                caption = content.caption?.text.orEmpty()
            )
        }
        else -> null
    }

    private fun isVideoFile(mime: String?, fileName: String?): Boolean {
        if (mime.orEmpty().lowercase().startsWith("video/")) return true
        return fileName.orEmpty().substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    }

    // ── Thumbnails ──────────────────────────────────────────────────────────────────────────

    // `<id>.jpg` is the real thumbnail, `<id>.mini.jpg` the ~40px blurry preview Telegram ships
    // inside the message. Keeping them apart is what lets the real one replace the blurry one later.
    private fun realThumbnailFile(videoId: String) = File(thumbDir, "$videoId.jpg")
    private fun placeholderFile(videoId: String) = File(thumbDir, "$videoId.mini.jpg")

    private fun imageWidth(file: File): Int {
        if (!file.exists() || file.length() == 0L) return 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth.coerceAtLeast(0)
    }

    /**
     * True when `<id>.jpg` is a proper thumbnail. Older versions saved the blurry preview under
     * that very name, so a tiny image does not count and gets replaced.
     */
    private fun hasRealThumbnail(videoId: String): Boolean = imageWidth(realThumbnailFile(videoId)) >= REAL_THUMBNAIL_MIN_WIDTH

    private fun bestThumbnailOnDisk(videoId: String): Uri? {
        val real = realThumbnailFile(videoId)
        if (imageWidth(real) >= REAL_THUMBNAIL_MIN_WIDTH) return Uri.fromFile(real)
        val placeholder = placeholderFile(videoId)
        if (real.exists()) {
            // Older versions saved the blurry preview under the real name. Move it aside so the real
            // thumbnail gets a new address and the image on screen is actually replaced.
            if (!placeholder.exists()) real.renameTo(placeholder) else real.delete()
        }
        if (placeholder.exists() && placeholder.length() > 0) return Uri.fromFile(placeholder)
        return null
    }

    private fun writePlaceholder(videoId: String, mini: ByteArray?): Uri? {
        if (mini == null || mini.isEmpty()) return null
        return try {
            val out = placeholderFile(videoId)
            out.writeBytes(mini)
            Uri.fromFile(out)
        } catch (_: Exception) {
            null
        }
    }

    /** Downloads Telegram's own thumbnail, trying twice, and stores it as a normal JPEG. */
    private suspend fun downloadThumbnail(
        tdLib: TdLibManager,
        videoId: String,
        thumbnail: TdApi.Thumbnail?
    ): File? {
        if (thumbnail == null) return null
        val usableFormat = thumbnail.format is TdApi.ThumbnailFormatJpeg ||
            thumbnail.format is TdApi.ThumbnailFormatPng ||
            thumbnail.format is TdApi.ThumbnailFormatWebp
        if (!usableFormat) return null

        val out = realThumbnailFile(videoId)
        repeat(THUMBNAIL_ATTEMPTS) { attempt ->
            try {
                val local = thumbnail.file.local
                var path: String? = local.path.takeIf { local.isDownloadingCompleted && !it.isNullOrBlank() && File(it).exists() }
                if (path == null) {
                    val downloaded = withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                        tdLib.send(TdApi.DownloadFile(thumbnail.file.id, 16, 0L, 0L, true))
                    }
                    if (downloaded != null && downloaded.local.isDownloadingCompleted) {
                        path = downloaded.local.path?.takeIf { it.isNotBlank() }
                    }
                }
                if (path != null && File(path).exists()) {
                    if (RemoteMediaProbe.saveThumbnailBytes(File(path).readBytes(), out)) return out
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Thumbnail download failed for $videoId (attempt ${attempt + 1}): ${e.message}")
            }
            delay(1_000L * (attempt + 1))
        }
        return null
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
        put("sourceId", v.sourceId ?: JSONObject.NULL)
    }

    private fun videoFromJson(o: JSONObject): Video {
        val thumb = if (o.isNull("thumb")) null else Uri.parse(o.getString("thumb")).takeIf { thumbnailFileExists(it) }
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
            source = VideoSource.TELEGRAM,
            sourceId = if (o.isNull("sourceId")) null else o.getString("sourceId")
        )
    }

    private fun thumbnailFileExists(uri: Uri): Boolean =
        uri.path?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

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

    private fun extensionFromMime(mime: String): String = when (mime.lowercase()) {
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-msvideo" -> "avi"
        "video/3gpp" -> "3gp"
        "video/mp2t" -> "ts"
        else -> "mp4"
    }

    companion object {
        private const val TAG = "TelegramVideoRepo"
        private const val CACHE_FILE = "telegram_videos.json"
        private const val THUMBNAIL_DIR = "video_thumbnails"
        private const val REAL_THUMBNAIL_MIN_WIDTH = 100
        private const val THUMBNAIL_ATTEMPTS = 2
        private const val THUMBNAIL_TIMEOUT_MS = 25_000L
        private const val LIGHT_PARALLELISM = 4
        private const val HEAVY_PARALLELISM = 2
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg"
        )

        fun videoId(chatId: Long, messageId: Long): String = "tg_video_${chatId}_$messageId"
    }
}
