package com.beatraxus.app.subtitles.domain

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

object VideoHashCalculator {

    private const val HASH_CHUNK_SIZE = 64 * 1024 // 64 KiB
    private const val MIN_FILE_SIZE = 128 * 1024  // 128 KiB

    private val hashCache = ConcurrentHashMap<String, String>()

    fun getCachedHash(videoId: String): String? = hashCache[videoId]

    fun clearCache() = hashCache.clear()

    suspend fun calculateHash(
        videoId: String,
        uri: Uri,
        contentResolver: ContentResolver
    ): String? = withContext(Dispatchers.IO) {
        hashCache[videoId]?.let { return@withContext it }

        try {
            coroutineContext.ensureActive()

            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val fileSize = channel.size()
                    if (fileSize < MIN_FILE_SIZE) {
                        return@withContext null
                    }

                    var hash = fileSize

                    // First 64 KiB
                    val buffer = ByteBuffer.allocate(HASH_CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    channel.position(0)
                    var bytesRead = 0
                    while (bytesRead < HASH_CHUNK_SIZE) {
                        coroutineContext.ensureActive()
                        val read = channel.read(buffer)
                        if (read <= 0) break
                        bytesRead += read
                    }

                    buffer.flip()
                    val longBufferFirst = buffer.asLongBuffer()
                    while (longBufferFirst.hasRemaining()) {
                        hash += longBufferFirst.get()
                    }

                    // Last 64 KiB
                    buffer.clear()
                    channel.position(fileSize - HASH_CHUNK_SIZE)
                    bytesRead = 0
                    while (bytesRead < HASH_CHUNK_SIZE) {
                        coroutineContext.ensureActive()
                        val read = channel.read(buffer)
                        if (read <= 0) break
                        bytesRead += read
                    }

                    buffer.flip()
                    val longBufferLast = buffer.asLongBuffer()
                    while (longBufferLast.hasRemaining()) {
                        hash += longBufferLast.get()
                    }

                    val computedHash = String.format("%016x", hash)
                    hashCache[videoId] = computedHash
                    return@withContext computedHash
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext null
        }
    }

    fun computeHashFromChannelForTesting(channel: FileChannel): String? {
        val fileSize = channel.size()
        if (fileSize < MIN_FILE_SIZE) return null

        var hash = fileSize

        val buffer = ByteBuffer.allocate(HASH_CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        var bytesRead = 0
        while (bytesRead < HASH_CHUNK_SIZE) {
            val read = channel.read(buffer)
            if (read <= 0) break
            bytesRead += read
        }

        buffer.flip()
        val longBufferFirst = buffer.asLongBuffer()
        while (longBufferFirst.hasRemaining()) {
            hash += longBufferFirst.get()
        }

        buffer.clear()
        channel.position(fileSize - HASH_CHUNK_SIZE)
        bytesRead = 0
        while (bytesRead < HASH_CHUNK_SIZE) {
            val read = channel.read(buffer)
            if (read <= 0) break
            bytesRead += read
        }

        buffer.flip()
        val longBufferLast = buffer.asLongBuffer()
        while (longBufferLast.hasRemaining()) {
            hash += longBufferLast.get()
        }

        return String.format("%016x", hash)
    }
}
