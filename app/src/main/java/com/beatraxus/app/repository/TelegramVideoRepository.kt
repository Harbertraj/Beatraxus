package com.beatraxus.app.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import com.beatraxus.app.model.parseTelegramChannelName
import com.beatraxus.app.telegram.TdLibManager
import com.beatraxus.app.telegram.TelegramVideoUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * Returns the videos currently in [channelUrl], or null if the channel couldn't be read
     * (so the caller keeps whatever it already has instead of wiping it).
     */
    suspend fun scanChannel(
        tdLib: TdLibManager,
        channelUrl: String,
        existing: Map<String, Video>
    ): List<Video>? {
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

        val semaphore = Semaphore(8)
        val videos = coroutineScope {
            messages.map { msg ->
                async(Dispatchers.IO) {
                    val candidate = candidateOf(msg) ?: return@async null
                    val id = videoId(msg.chatId, msg.id)

                    val title = candidate.fileName.substringBeforeLast('.', candidate.fileName).trim()
                        .ifBlank { candidate.caption.lineSequence().firstOrNull().orEmpty().trim().take(80) }
                        .ifBlank { "Video ${msg.id}" }
                    val mime = candidate.mime.ifBlank { mimeFromName(candidate.fileName) }
                    val displayName = candidate.fileName.ifBlank { "$title.${extensionFromMime(mime)}" }
                    val size = candidate.file.size.toLong()

                    val thumbnail = existing[id]?.thumbnailUri?.takeIf { thumbnailFileExists(it) }
                        ?: semaphore.withPermit { saveThumbnail(tdLib, id, candidate.thumbnail, candidate.mini) }

                    Video(
                        id = id,
                        uri = TelegramVideoUri.build(msg.chatId, msg.id, candidate.file.id, size),
                        title = title,
                        displayName = displayName,
                        folderPath = "telegram://$username",
                        durationMs = candidate.durationSec * 1000L,
                        sizeBytes = size,
                        resolutionWidth = candidate.width,
                        resolutionHeight = candidate.height,
                        mimeType = mime,
                        dateAdded = msg.date.toLong(),
                        thumbnailUri = thumbnail,
                        source = VideoSource.TELEGRAM,
                        sourceId = sourceId
                    )
                }
            }.awaitAll().filterNotNull()
        }
        Log.d(TAG, "Found ${videos.size} videos in $username")
        return videos.sortedByDescending { it.dateAdded }
    }

    private class Candidate(
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

    private suspend fun saveThumbnail(
        tdLib: TdLibManager,
        videoId: String,
        thumbnail: TdApi.Thumbnail?,
        mini: ByteArray?
    ): Uri? = withContext(Dispatchers.IO) {
        val out = File(thumbDir, "$videoId.jpg")
        if (out.exists() && out.length() > 0) return@withContext Uri.fromFile(out)

        val usableFormat = thumbnail != null && (
            thumbnail.format is TdApi.ThumbnailFormatJpeg ||
                thumbnail.format is TdApi.ThumbnailFormatPng ||
                thumbnail.format is TdApi.ThumbnailFormatWebp
            )
        if (thumbnail != null && usableFormat) {
            try {
                val local = thumbnail.file.local
                var path: String? = if (local.isDownloadingCompleted) local.path else null
                if (path.isNullOrBlank()) {
                    val downloaded = withTimeoutOrNull(10_000) {
                        tdLib.send(TdApi.DownloadFile(thumbnail.file.id, 16, 0L, 0L, true))
                    }
                    path = downloaded?.takeIf { it.local.isDownloadingCompleted }?.local?.path
                }
                if (!path.isNullOrBlank() && File(path).exists()) {
                    File(path).copyTo(out, overwrite = true)
                    return@withContext Uri.fromFile(out)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Thumbnail download failed for $videoId: ${e.message}")
            }
        }

        // Tiny blurred preview that ships with the message itself: better than an empty card.
        if (mini != null && mini.isNotEmpty()) {
            try {
                out.writeBytes(mini)
                return@withContext Uri.fromFile(out)
            } catch (_: Exception) {}
        }
        null
    }

    private fun thumbnailFileExists(uri: Uri): Boolean =
        uri.path?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true

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
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg"
        )

        fun videoId(chatId: Long, messageId: Long): String = "tg_video_${chatId}_$messageId"
    }
}
