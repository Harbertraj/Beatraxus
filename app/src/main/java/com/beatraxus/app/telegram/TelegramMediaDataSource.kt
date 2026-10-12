package com.beatraxus.app.telegram

import android.media.MediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * [MediaDataSource] over a Telegram file, so `MediaMetadataRetriever` can read the runtime and a
 * preview frame of a video without downloading all of it.
 *
 * Each read asks TDLib for a ~1 MiB window starting at the requested offset, waits until that range
 * is on disk and reads it from TDLib's sparse local file (the same trick [TelegramVideoDataSource]
 * uses for playback).
 */
class TelegramMediaDataSource(
    private val tdLib: TdLibManager,
    private val fileId: Int,
    knownSize: Long
) : MediaDataSource() {

    private var totalSize: Long = if (knownSize > 0) knownSize else -1L
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    // Byte range [windowStart, windowEnd) known to be readable.
    private var windowStart = 0L
    private var windowEnd = 0L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (totalSize in 0..position) return -1

        if (raf == null || position < windowStart || position >= windowEnd) {
            fetchWindow(position)
        }
        if (position < windowStart || position >= windowEnd) throw IOException("Telegram data not available at $position")

        val file = raf ?: throw IOException("Telegram file is not open")
        val n = minOf(size.toLong(), windowEnd - position).toInt()
        file.seek(position)
        val read = file.read(buffer, offset, n)
        return if (read <= 0) -1 else read
    }

    @Synchronized
    override fun getSize(): Long = totalSize

    @Synchronized
    override fun close() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        rafPath = null
    }

    private fun fetchWindow(position: Long) {
        repeat(MAX_ATTEMPTS) {
            val remaining = if (totalSize > 0) totalSize - position else Long.MAX_VALUE
            val limit = minOf(WINDOW_BYTES, remaining)
            val file = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(TIMEOUT_MS) {
                    tdLib.send(TdApi.DownloadFile(fileId, PRIORITY, position, limit, true))
                    tdLib.send(TdApi.GetFile(fileId))
                }
            } ?: throw IOException("Telegram download timed out")

            if (totalSize < 0) {
                val size = maxOf(file.size.toLong(), file.expectedSize.toLong())
                if (size > 0) totalSize = size
            }

            val path = file.local.path
            if (!path.isNullOrBlank()) {
                if (file.local.isDownloadingCompleted) {
                    windowStart = 0L
                    windowEnd = if (totalSize > 0) totalSize else File(path).length()
                } else {
                    windowStart = file.local.downloadOffset.toLong()
                    windowEnd = windowStart + file.local.downloadedPrefixSize.toLong()
                }
                if (raf == null || rafPath != path) {
                    try { raf?.close() } catch (_: Exception) {}
                    raf = RandomAccessFile(File(path), "r")
                    rafPath = path
                }
                if (position >= windowStart && position < windowEnd) return
            }
        }
        throw IOException("Telegram did not deliver the requested range")
    }

    companion object {
        private const val WINDOW_BYTES = 1L * 1024 * 1024
        private const val TIMEOUT_MS = 30_000L
        private const val MAX_ATTEMPTS = 3
        private const val PRIORITY = 8
    }
}
