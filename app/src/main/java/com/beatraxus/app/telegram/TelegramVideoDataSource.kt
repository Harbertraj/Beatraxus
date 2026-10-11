package com.beatraxus.app.telegram

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Playable address of a video that lives in a Telegram message:
 * `tgvideo://stream?chat=<chatId>&msg=<messageId>&file=<tdlibFileId>&size=<bytes>`
 *
 * The file id / size are only hints: the data source re-resolves the file from the message
 * when the id has gone stale (e.g. after a TDLib database reset).
 */
object TelegramVideoUri {
    const val SCHEME = "tgvideo"

    fun build(chatId: Long, messageId: Long, fileId: Int, size: Long): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("stream")
            .appendQueryParameter("chat", chatId.toString())
            .appendQueryParameter("msg", messageId.toString())
            .appendQueryParameter("file", fileId.toString())
            .appendQueryParameter("size", size.toString())
            .build()

    fun isTelegramVideo(uri: Uri?): Boolean = uri?.scheme == SCHEME
}

/**
 * ExoPlayer [DataSource] that streams a Telegram video through TDLib.
 *
 * TDLib downloads into a sparse local file. We ask it for a window of bytes starting at the
 * position ExoPlayer wants (so seeks and MP4 files with the index at the end work), wait until
 * that range is on disk and read it with a [RandomAccessFile]. The window is extended while the
 * player advances, so pausing a movie doesn't download the whole file in the background.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TelegramVideoDataSource(
    private val tdLib: TdLibManager,
    private val openedFileIds: MutableSet<Int>
) : BaseDataSource(/* isNetwork = */ true) {

    private var openUri: Uri? = null
    private var opened = false

    private var chatId = 0L
    private var messageId = 0L
    private var fileId = 0
    private var totalSize = -1L

    private var position = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    // Byte range [rangeStart, rangeEnd) TDLib reported as readable on disk.
    private var rangeStart = 0L
    private var rangeEnd = 0L

    // Exclusive end of the window we asked TDLib for (Long.MAX_VALUE = until end of file).
    private var requestedEnd = 0L
    private var lastRequestAt = 0L
    private var lastRequestOffset = -1L

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        openUri = uri
        transferInitializing(dataSpec)

        chatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: throw IOException("Invalid Telegram video address")
        messageId = uri.getQueryParameter("msg")?.toLongOrNull() ?: throw IOException("Invalid Telegram video address")
        val hintedFileId = uri.getQueryParameter("file")?.toIntOrNull() ?: 0
        val hintedSize = uri.getQueryParameter("size")?.toLongOrNull() ?: -1L

        blocking {
            tdLib.ensureClientStarted()
            if (!tdLib.awaitTdlibReady(20_000)) throw IOException("Telegram is not connected")
            resolveFile(hintedFileId, hintedSize)
        }

        if (totalSize > 0 && dataSpec.position > totalSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0 -> totalSize - position
            else -> C.LENGTH_UNSET.toLong()
        }
        rangeStart = 0L
        rangeEnd = 0L

        // Start downloading right away so the first read doesn't pay the request latency.
        blocking { requestWindow(position) }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (totalSize > 0 && position >= totalSize) return C.RESULT_END_OF_INPUT

        var attempts = 0
        while (true) {
            if (raf == null || position < rangeStart || position >= rangeEnd) {
                awaitPosition(position)
            }

            var toRead = minOf(length.toLong(), rangeEnd - position)
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) toRead = minOf(toRead, bytesRemaining)
            if (toRead <= 0L) {
                rangeEnd = rangeStart // force a fresh look at the file state
                if (++attempts > 3) throw IOException("Telegram file is not readable")
                continue
            }

            val file = raf ?: throw IOException("Telegram file is not open")
            file.seek(position)
            val read = file.read(buffer, offset, toRead.toInt())

            if (read <= 0) {
                // The sparse file isn't backed by the bytes TDLib promised (e.g. cache was wiped).
                rangeEnd = rangeStart
                if (++attempts > 3) throw IOException("Unexpected end of Telegram file")
                continue
            }

            position += read
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
            bytesTransferred(read)
            maybeExtendWindow(position)
            return read
        }
    }

    override fun getUri(): Uri? = openUri

    override fun close() {
        openUri = null
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        rafPath = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    // ── TDLib helpers ───────────────────────────────────────────────────────────────────────

    private suspend fun resolveFile(hintedFileId: Int, hintedSize: Long) {
        var file: TdApi.File? = null
        if (hintedFileId != 0) {
            file = try { tdLib.send(TdApi.GetFile(hintedFileId)) } catch (_: Exception) { null }
            // A different size means the id now points at some other file: don't trust it.
            val size = file?.size?.toLong() ?: 0L
            if (file != null && hintedSize > 0 && size > 0 && size != hintedSize) file = null
        }
        if (file == null) file = fileFromMessage() ?: throw IOException("Video not found in Telegram")

        fileId = file.id
        totalSize = maxOf(file.size.toLong(), file.expectedSize.toLong()).takeIf { it > 0 } ?: hintedSize
        openedFileIds.add(fileId)
    }

    private suspend fun fileFromMessage(): TdApi.File? = try {
        when (val content = tdLib.getMessage(chatId, messageId).content) {
            is TdApi.MessageVideo -> content.video.video
            is TdApi.MessageDocument -> content.document.document
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not refresh Telegram file for message $messageId: ${e.message}")
        null
    }

    /** Asks TDLib to download a window of the file starting at [from]. */
    private suspend fun requestWindow(from: Long) {
        val remaining = if (totalSize > 0) totalSize - from else Long.MAX_VALUE
        // Near the end of the file just fetch the rest instead of a tiny trailing window.
        val limit = if (remaining <= WINDOW_BYTES * 2) 0L else WINDOW_BYTES
        tdLib.send(TdApi.DownloadFile(fileId, 32, from, limit, false))
        requestedEnd = if (limit == 0L) Long.MAX_VALUE else from + limit
        lastRequestAt = System.currentTimeMillis()
        lastRequestOffset = from
    }

    /** Keeps the download window ahead of the playback position. */
    private fun maybeExtendWindow(pos: Long) {
        if (requestedEnd == Long.MAX_VALUE) return
        if (pos + EXTEND_MARGIN_BYTES < requestedEnd) return
        if (System.currentTimeMillis() - lastRequestAt < 500) return
        try {
            blocking { requestWindow(pos) }
        } catch (e: IOException) {
            // Not fatal: awaitPosition() will re-request if the player really runs dry.
            Log.w(TAG, "Window extension failed: ${e.message}")
        }
    }

    /** Refreshes the readable range from [f]; returns false while nothing is readable yet. */
    private fun refreshRange(f: TdApi.File): Boolean {
        val path = f.local.path
        if (path.isNullOrBlank()) return false

        if (f.local.isDownloadingCompleted) {
            rangeStart = 0L
            rangeEnd = if (totalSize > 0) totalSize else File(path).length()
        } else {
            rangeStart = f.local.downloadOffset.toLong()
            rangeEnd = rangeStart + f.local.downloadedPrefixSize.toLong()
        }

        if (raf == null || rafPath != path) {
            try { raf?.close() } catch (_: Exception) {}
            raf = try {
                RandomAccessFile(File(path), "r")
            } catch (e: IOException) {
                rafPath = null
                return false
            }
            rafPath = path
        }
        return true
    }

    /** Blocks until byte [pos] is readable on disk, (re)requesting downloads as needed. */
    private fun awaitPosition(pos: Long) {
        var lastProgressMark = -1L
        var lastProgressAt = System.currentTimeMillis()

        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()

            val f = blocking { tdLib.send(TdApi.GetFile(fileId)) }
            if (refreshRange(f) && pos >= rangeStart && pos < rangeEnd) return

            val now = System.currentTimeMillis()
            val coveredEnd = f.local.downloadOffset.toLong() + f.local.downloadedPrefixSize.toLong()
            val active = f.local.isDownloadingActive
            val needsNewRequest = !active || pos < f.local.downloadOffset.toLong() || pos - coveredEnd > JUMP_BYTES
            val justRequested = lastRequestOffset == pos && now - lastRequestAt < RETRY_REQUEST_MS
            if (needsNewRequest && !justRequested) {
                blocking { requestWindow(pos) }
            }

            if (coveredEnd != lastProgressMark) {
                lastProgressMark = coveredEnd
                lastProgressAt = now
            }
            if (now - lastProgressAt > WAIT_TIMEOUT_MS) {
                throw IOException("Timed out waiting for Telegram data at byte $pos")
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    /** Runs a suspend call from ExoPlayer's loader thread, translating interrupts into IO errors. */
    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) { block() }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException()
    }

    companion object {
        private const val TAG = "TgVideoDataSource"
        private const val WINDOW_BYTES = 48L * 1024 * 1024
        private const val EXTEND_MARGIN_BYTES = 24L * 1024 * 1024
        private const val JUMP_BYTES = 8L * 1024 * 1024
        private const val POLL_MS = 30L
        private const val WAIT_TIMEOUT_MS = 45_000L
        private const val RETRY_REQUEST_MS = 4_000L
    }
}

/**
 * Wraps the player's normal [DataSource.Factory] and sends `tgvideo://` addresses through
 * [TelegramVideoDataSource]; every other address behaves exactly as before.
 *
 * One instance belongs to one player. Call [purgeDownloads] when that player is released so the
 * streamed video doesn't linger in TDLib's cache.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TelegramAwareDataSourceFactory(
    private val tdLib: TdLibManager,
    private val fallback: DataSource.Factory
) : DataSource.Factory {

    private val openedFileIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun createDataSource(): DataSource =
        RoutingDataSource(fallback.createDataSource(), TelegramVideoDataSource(tdLib, openedFileIds))

    /** Deletes the files this player streamed from TDLib's cache (also cancels running downloads). */
    suspend fun purgeDownloads() {
        val ids = openedFileIds.toList()
        openedFileIds.clear()
        ids.forEach { id ->
            try { tdLib.send(TdApi.DeleteFile(id)) } catch (_: Exception) {}
        }
    }

    private class RoutingDataSource(
        private val fallback: DataSource,
        private val telegram: DataSource
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            fallback.addTransferListener(transferListener)
            telegram.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (TelegramVideoUri.isTelegramVideo(dataSpec.uri)) telegram else fallback
            active = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }
}
