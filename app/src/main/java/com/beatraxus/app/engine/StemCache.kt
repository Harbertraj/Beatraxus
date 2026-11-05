package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object StemCache {
    private const val TAG = "StemCache"
    val STEM_FILENAMES = arrayOf("vocals.pcm", "drums.pcm", "bass.pcm", "other.pcm")
    private const val DONE_FILENAME = "done"

    fun getStemsDir(context: Context, songId: String): File {
        val safeId = songId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(File(context.cacheDir, "stems"), safeId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isStemsCached(context: Context, songId: String): Boolean {
        val dir = getStemsDir(context, songId)
        if (!dir.exists() || !dir.isDirectory) return false
        if (!File(dir, DONE_FILENAME).exists()) return false
        for (name in STEM_FILENAMES) {
            val f = File(dir, name)
            if (!f.exists() || f.length() <= 0) return false
        }
        return true
    }

    fun markStemsDone(context: Context, songId: String) {
        val dir = getStemsDir(context, songId)
        try {
            File(dir, DONE_FILENAME).createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write done marker for $songId: ${e.message}")
        }
    }

    fun deleteStems(context: Context, songId: String) {
        val dir = getStemsDir(context, songId)
        try {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete stems directory for $songId: ${e.message}")
        }
    }

    fun saveStemsToCache(context: Context, songId: String, stems: Array<FloatArray>) {
        val dir = getStemsDir(context, songId)
        for (i in 0 until minOf(stems.size, STEM_FILENAMES.size)) {
            val file = File(dir, STEM_FILENAMES[i])
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    val channel = raf.channel
                    val floatData = stems[i]
                    val byteBuf = ByteBuffer.allocateDirect(floatData.size * 4).order(ByteOrder.nativeOrder())
                    byteBuf.asFloatBuffer().put(floatData)
                    byteBuf.rewind()
                    channel.write(byteBuf)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save stem ${STEM_FILENAMES[i]} to cache: ${e.message}", e)
            }
        }
        markStemsDone(context, songId)
    }

    /**
     * Reads [frameCount] stereo frames (i.e. [frameCount] * 2 floats) from stem index [stemIndex]
     * starting at frame offset [frameOffset] into [outBuffer].
     * Returns true if read succeeded, false otherwise.
     */
    fun readStemFrames(
        context: Context,
        songId: String,
        stemIndex: Int,
        frameOffset: Long,
        frameCount: Int,
        outBuffer: FloatArray
    ): Boolean {
        if (stemIndex !in STEM_FILENAMES.indices) return false
        val dir = getStemsDir(context, songId)
        val file = File(dir, STEM_FILENAMES[stemIndex])
        if (!file.exists() || file.length() <= 0) return false

        val byteOffset = frameOffset * 8 // 2 channels * 4 bytes per float
        val bytesToRead = frameCount * 8
        val fileSize = file.length()
        if (byteOffset >= fileSize) return false

        val actualBytesToRead = minOf(bytesToRead.toLong(), fileSize - byteOffset).toInt()
        val floatsToRead = actualBytesToRead / 4

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                channel.position(byteOffset)
                val byteBuf = ByteBuffer.allocateDirect(actualBytesToRead).order(ByteOrder.nativeOrder())
                channel.read(byteBuf)
                byteBuf.rewind()
                byteBuf.asFloatBuffer().get(outBuffer, 0, floatsToRead)
                if (floatsToRead < outBuffer.size) {
                    outBuffer.fill(0f, floatsToRead, outBuffer.size)
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading stem frames: ${e.message}")
            false
        }
    }
}
