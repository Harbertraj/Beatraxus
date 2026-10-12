package com.beatraxus.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the runtime, size and a preview frame of a video that isn't a local file (cloud drives,
 * Telegram) by letting [MediaMetadataRetriever] pull only the few ranges it needs from a
 * [MediaDataSource].
 */
object RemoteMediaProbe {
    private const val TAG = "RemoteMediaProbe"
    const val THUMBNAIL_DIR = "video_thumbnails"
    private const val THUMBNAIL_MAX_WIDTH = 480

    data class Result(val frame: Bitmap?, val durationMs: Long, val width: Int, val height: Int)

    /** Blocks while ranges are fetched, so call it from a background thread. Always closes [source]. */
    fun probe(source: MediaDataSource, needFrame: Boolean): Result? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val t = width; width = height; height = t
            }

            var frame: Bitmap? = null
            if (needFrame) {
                val atUs = if (duration > 4_000L) 1_000_000L else 0L
                frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame = frame?.let { scaleDown(it, THUMBNAIL_MAX_WIDTH) }
            }
            return Result(frame, duration, width, height)
        } catch (e: Exception) {
            Log.d(TAG, "Could not probe video: ${e.message}")
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { source.close() } catch (_: Exception) {}
        }
    }

    fun thumbnailFile(filesDir: File, videoId: String): File =
        File(File(filesDir, THUMBNAIL_DIR).apply { mkdirs() }, "$videoId.jpg")

    fun saveThumbnail(bitmap: Bitmap, out: File): Boolean = try {
        val tmp = File(out.parentFile, "${out.name}.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (out.exists()) out.delete()
        tmp.renameTo(out) && out.length() > 0
    } catch (e: Exception) {
        Log.d(TAG, "Could not save thumbnail: ${e.message}")
        false
    }

    /** Decodes downloaded image bytes (any format Android reads), shrinks them and stores a JPEG. */
    fun saveThumbnailBytes(bytes: ByteArray, out: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMBNAIL_MAX_WIDTH) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return false
            saveThumbnail(scaleDown(decoded, THUMBNAIL_MAX_WIDTH), out)
        } catch (e: Exception) {
            Log.d(TAG, "Could not decode thumbnail: ${e.message}")
            false
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val height = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    }
}

/**
 * [MediaDataSource] over plain HTTP range requests. Reads ahead in ~1 MiB windows so the many small
 * reads of the metadata retriever don't each cost a round trip.
 */
class HttpRangeMediaDataSource(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    knownSize: Long
) : MediaDataSource() {

    private var totalSize: Long = if (knownSize > 0) knownSize else -1L
    private var window = ByteArray(0)
    private var windowStart = -1L
    private var windowLen = 0

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (totalSize in 0..position) return -1
        if (!inWindow(position)) fetch(position, size)
        if (!inWindow(position)) return -1
        val start = (position - windowStart).toInt()
        val n = minOf(size, windowLen - start)
        System.arraycopy(window, start, buffer, offset, n)
        return n
    }

    @Synchronized
    override fun getSize(): Long = totalSize

    override fun close() {
        window = ByteArray(0)
        windowLen = 0
        windowStart = -1L
    }

    private fun inWindow(position: Long) =
        windowStart >= 0 && position >= windowStart && position < windowStart + windowLen

    private fun fetch(position: Long, wanted: Int) {
        val want = maxOf(wanted, WINDOW_BYTES).coerceAtMost(MAX_WINDOW_BYTES).toLong()
        var end = position + want - 1
        if (totalSize > 0) end = minOf(end, totalSize - 1)

        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$position-$end")
        }.build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 416 -> { // asked for bytes past the end of the file
                    windowStart = -1L
                    windowLen = 0
                    return
                }
                response.code != 206 && response.code != 200 -> throw IOException("HTTP ${response.code}")
                response.code == 200 && position > 0 -> throw IOException("Server ignores range requests")
            }
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { totalSize = it }
            if (totalSize < 0 && response.code == 200) {
                response.header("Content-Length")?.toLongOrNull()?.let { totalSize = it }
            }
            val body = response.body ?: throw IOException("Empty response")
            val target = if (window.size >= want.toInt()) window else ByteArray(want.toInt())
            val read = readFully(body.byteStream(), target, want.toInt())
            window = target
            windowStart = position
            windowLen = read
        }
    }

    private fun readFully(input: InputStream, into: ByteArray, max: Int): Int {
        var total = 0
        while (total < max) {
            val n = input.read(into, total, max - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    companion object {
        private const val WINDOW_BYTES = 1024 * 1024
        private const val MAX_WINDOW_BYTES = 4 * 1024 * 1024
    }
}
