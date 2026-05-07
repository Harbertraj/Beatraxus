package com.beatflowy.app.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Metadata key for lyrics. Added in API 31, but the value 23 is consistent.
 */
private const val METADATA_KEY_LYRIC_INT = 23

class EmbeddedLyricsSource(private val context: Context) {

    suspend fun getLyrics(songPath: String): LyricsResult? = withContext(Dispatchers.IO) {
        val file = File(songPath)
        if (!file.exists()) return@withContext null
        extract(
            configureRetriever = { it.setDataSource(songPath) },
            openTagStream = { file.inputStream() }
        )
    }

    suspend fun getLyrics(uri: Uri): LyricsResult? = withContext(Dispatchers.IO) {
        extract(
            configureRetriever = { retriever ->
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: retriever.setDataSource(context, uri)
            },
            openTagStream = { context.contentResolver.openInputStream(uri) }
        )
    }

    private fun extract(
        configureRetriever: (MediaMetadataRetriever) -> Unit,
        openTagStream: () -> InputStream?
    ): LyricsResult? {
        val retriever = MediaMetadataRetriever()
        try {
            configureRetriever(retriever)
            
            // 1. Try MediaMetadataRetriever first (Priority)
            val mmrLyrics = extractFromMMR(retriever)
            if (mmrLyrics != null) return mmrLyrics

            // 2. Fallback to manual ID3 Parsing for USLT/SYLT
            openTagStream()?.use { input ->
                return parseId3Tags(input)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            runCatching { retriever.release() }
        }
        return null
    }

    private fun extractFromMMR(retriever: MediaMetadataRetriever): LyricsResult? {
        val raw = try {
            retriever.extractMetadata(METADATA_KEY_LYRIC_INT)
        } catch (e: Exception) {
            null
        }
        
        return if (!raw.isNullOrBlank()) {
            LyricsResult(
                type = if (isSynced(raw)) LyricsType.SYNCED else LyricsType.PLAIN,
                content = raw.trim()
            )
        } else null
    }

    private fun isSynced(text: String): Boolean {
        // Simple check for LRC format [00:00.00]
        return text.contains(Regex("\\[\\d+:\\d+[.:]\\d+\\]"))
    }

    private fun parseId3Tags(input: InputStream): LyricsResult? {
        val header = ByteArray(10)
        if (!readFully(input, header) || header.copyOfRange(0, 3).decodeToString() != "ID3") return null

        val version = header[3].toInt() and 0xFF
        val tagSize = decodeSynchsafeInt(header.copyOfRange(6, 10))
        if (tagSize <= 0) return null

        val tagData = ByteArray(tagSize)
        if (!readFully(input, tagData)) return null

        var offset = 0
        var usltLyrics: String? = null

        while (offset + 10 <= tagData.size) {
            val frameId = try {
                tagData.copyOfRange(offset, offset + 4).decodeToString()
            } catch (e: Exception) {
                ""
            }
            
            if (frameId.any { it !in 'A'..'Z' && it !in '0'..'9' }) break

            val frameSizeBytes = tagData.copyOfRange(offset + 4, offset + 8)
            val frameSize = if (version >= 4) decodeSynchsafeInt(frameSizeBytes) else decodeInt(frameSizeBytes)
            
            if (frameSize <= 0 || offset + 10 + frameSize > tagData.size) break
            
            val frameContent = tagData.copyOfRange(offset + 10, offset + 10 + frameSize)

            when (frameId) {
                "SYLT" -> {
                    val synced = decodeSyltFrame(frameContent)
                    if (synced != null) return LyricsResult(LyricsType.SYNCED, synced)
                }
                "USLT" -> {
                    val unsynced = decodeUsltFrame(frameContent)
                    if (!unsynced.isNullOrBlank()) usltLyrics = unsynced
                }
            }
            offset += 10 + frameSize
        }

        return usltLyrics?.let { LyricsResult(LyricsType.PLAIN, it) }
    }

    private fun decodeUsltFrame(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        var offset = 4 // encoding (1) + lang (3)
        
        // Skip description
        val termLen = findTerminator(data, offset, encoding)
        offset += termLen + (if (encoding == 1 || encoding == 2) 2 else 1)
        if (offset >= data.size) return null

        return decodeText(data.copyOfRange(offset, data.size), encoding)
    }

    private fun decodeSyltFrame(data: ByteArray): String? {
        if (data.size < 6) return null
        val encoding = data[0].toInt() and 0xFF
        // data[1..3] is lang
        val timestampFormat = data[4].toInt() and 0xFF // 1 = ms, 2 = frames
        val contentType = data[5].toInt() and 0xFF // 1 = lyrics
        if (contentType != 1) return null

        var offset = 6
        // Skip description
        val descTermLen = findTerminator(data, offset, encoding)
        offset += descTermLen + (if (encoding == 1 || encoding == 2) 2 else 1)
        
        val lrcBuilder = StringBuilder()
        while (offset + 4 < data.size) {
            val textTermLen = findTerminator(data, offset, encoding)
            val textBytes = data.copyOfRange(offset, offset + textTermLen)
            val text = decodeText(textBytes, encoding) ?: ""
            offset += textTermLen + (if (encoding == 1 || encoding == 2) 2 else 1)
            
            if (offset + 4 > data.size) break
            
            val timestamp = decodeInt(data.copyOfRange(offset, offset + 4)).toLong()
            // We assume ms format (1) as it's the standard for lyrics
            
            if (text.isNotBlank() || lrcBuilder.isNotEmpty()) {
                lrcBuilder.append(formatLrcTime(timestamp)).append(text).append("\n")
            }
            offset += 4
        }
        
        return lrcBuilder.toString().takeIf { it.isNotBlank() }
    }

    private fun formatLrcTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
    }

    private fun findTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        var i = start
        val isWide = encoding == 1 || encoding == 2
        while (i < data.size) {
            if (isWide) {
                if (i + 1 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i - start
                i += 2
            } else {
                if (data[i] == 0.toByte()) return i - start
                i++
            }
        }
        return data.size - start
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String? = runCatching {
        when (encoding) {
            0 -> String(bytes, Charsets.ISO_8859_1)
            1 -> String(bytes, Charsets.UTF_16)
            2 -> String(bytes, Charsets.UTF_16BE)
            3 -> String(bytes, Charsets.UTF_8)
            else -> String(bytes)
        }
    }.getOrNull()?.trim()

    private fun decodeInt(bytes: ByteArray): Int {
        var res = 0
        for (b in bytes) res = (res shl 8) or (b.toInt() and 0xFF)
        return res
    }

    private fun decodeSynchsafeInt(bytes: ByteArray): Int {
        var res = 0
        for (b in bytes) res = (res shl 7) or (b.toInt() and 0x7F)
        return res
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) return false
            total += read
        }
        return true
    }
}
