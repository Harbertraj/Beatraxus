package com.beatflowy.app.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbeddedLyricsSource(private val context: Context) {
    suspend fun getEmbeddedLyrics(songPath: String): String? = withContext(Dispatchers.IO) {
        val file = File(songPath)
        if (!file.exists()) return@withContext null
        extractEmbeddedLyrics(
            configureRetriever = { retriever ->
                retriever.setDataSource(songPath)
            },
            openTagStream = { file.inputStream() }
        )
    }

    suspend fun getLyrics(uri: Uri): String? = withContext(Dispatchers.IO) {
        extractEmbeddedLyrics(
            configureRetriever = { retriever ->
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: retriever.setDataSource(context, uri)
            },
            openTagStream = { context.contentResolver.openInputStream(uri) }
        )
    }

    private fun extractEmbeddedLyrics(
        configureRetriever: (MediaMetadataRetriever) -> Unit,
        openTagStream: () -> InputStream?
    ): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            configureRetriever(retriever)

            val metadataLyrics = extractLyricsFromMetadata(retriever)
            if (!metadataLyrics.isNullOrBlank()) {
                return metadataLyrics
            }

            openTagStream()?.use { input ->
                parseUsltLyrics(input)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun extractLyricsFromMetadata(retriever: MediaMetadataRetriever): String? {
        val candidates = listOf(23)

        return candidates
            .asSequence()
            .mapNotNull { key -> runCatching { retriever.extractMetadata(key) }.getOrNull() }
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
    }

    private fun parseUsltLyrics(input: InputStream): String? {
        val header = ByteArray(10)
        if (!readFully(input, header)) return null
        if (header.copyOfRange(0, 3).decodeToString() != "ID3") return null

        val versionMajor = header[3].toInt() and 0xFF
        val tagSize = decodeSynchsafeInt(header.copyOfRange(6, 10))
        if (tagSize <= 0) return null

        val tagBytes = ByteArray(tagSize)
        if (!readFully(input, tagBytes)) return null

        var offset = 0
        while (offset + 10 <= tagBytes.size) {
            val frameId = tagBytes.copyOfRange(offset, offset + 4).decodeToString()
            val frameSizeBytes = tagBytes.copyOfRange(offset + 4, offset + 8)
            val frameSize = when (versionMajor) {
                4 -> decodeSynchsafeInt(frameSizeBytes)
                else -> decodeInt(frameSizeBytes)
            }

            if (frameId.isBlank() || frameSize <= 0 || offset + 10 + frameSize > tagBytes.size) break

            if (frameId == "USLT") {
                val frameData = tagBytes.copyOfRange(offset + 10, offset + 10 + frameSize)
                return decodeUsltFrame(frameData)
            }

            offset += 10 + frameSize
        }

        return null
    }

    private fun decodeUsltFrame(frameData: ByteArray): String? {
        if (frameData.size <= 4) return null

        val encoding = frameData[0].toInt() and 0xFF
        var offset = 4 // encoding + 3-byte language

        offset += when (encoding) {
            1, 2 -> findUtf16Terminator(frameData, offset) + 2
            else -> findSingleByteTerminator(frameData, offset) + 1
        } - offset

        if (offset >= frameData.size) return null

        val lyricsBytes = frameData.copyOfRange(offset, frameData.size)
        return decodeText(lyricsBytes, encoding)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String? = when (encoding) {
        0 -> bytes.toString(Charsets.ISO_8859_1)
        1 -> if (hasUtf16Bom(bytes)) bytes.toString(Charsets.UTF_16) else bytes.toString(Charsets.UTF_16BE)
        2 -> bytes.toString(Charsets.UTF_16BE)
        3 -> bytes.toString(Charsets.UTF_8)
        else -> null
    }

    private fun hasUtf16Bom(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && (
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            )
    }

    private fun findSingleByteTerminator(bytes: ByteArray, startIndex: Int): Int {
        for (index in startIndex until bytes.size) {
            if (bytes[index] == 0.toByte()) return index
        }
        return bytes.size
    }

    private fun findUtf16Terminator(bytes: ByteArray, startIndex: Int): Int {
        var index = startIndex
        while (index + 1 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                return index
            }
            index += 2
        }
        return bytes.size
    }

    private fun decodeInt(bytes: ByteArray): Int {
        return bytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
    }

    private fun decodeSynchsafeInt(bytes: ByteArray): Int {
        return bytes.fold(0) { acc, byte -> (acc shl 7) or (byte.toInt() and 0x7F) }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read <= 0) return false
            totalRead += read
        }
        return true
    }
}
