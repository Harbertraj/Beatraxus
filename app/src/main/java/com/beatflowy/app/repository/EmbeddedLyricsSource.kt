package com.beatflowy.app.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT
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
        
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return@withContext null

            // 1. Try SYLT (Synchronized Lyrics) via JAudioTagger
            // val syltFrame = tag.getFirstField(FieldKey.LYRICS) // JAudioTagger might consolidate these
            // However, JAudioTagger's high-level API might not expose SYLT easily. 
            // Let's look for specific frames if it's an ID3 tag.
            
            val syltContent = extractSylt(tag)
            if (syltContent != null) return@withContext LyricsResult(LyricsType.SYNCED, syltContent)

            // 2. Try USLT (Unsynchronized Lyrics)
            val uslt = tag.getFirst(FieldKey.LYRICS)
            if (uslt.isNotBlank()) {
                return@withContext LyricsResult(
                    type = if (isSynced(uslt)) LyricsType.SYNCED else LyricsType.PLAIN,
                    content = uslt.trim()
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun getLyrics(uri: Uri): LyricsResult? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext getLyrics(uri.path ?: return@withContext null)
        }
        
        // Fallback for content URIs using MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val lyrics = retriever.extractMetadata(METADATA_KEY_LYRIC_INT)
            if (!lyrics.isNullOrBlank()) {
                return@withContext LyricsResult(
                    type = if (isSynced(lyrics)) LyricsType.SYNCED else LyricsType.PLAIN,
                    content = lyrics.trim()
                )
            }
        } catch (e: Exception) {
            // Silently fail for cloud URLs as they likely need headers and we use online sources anyway
        }
        null
    }

    private fun extractSylt(tag: org.jaudiotagger.tag.Tag): String? {
        try {
            val fields = tag.getFields("SYLT")
            if (fields.isEmpty()) return null
            
            val lrcBuilder = StringBuilder()
            for (field in fields) {
                val body = when (field) {
                    is ID3v23Frame -> field.body as? FrameBodySYLT
                    is ID3v24Frame -> field.body as? FrameBodySYLT
                    else -> null
                }
                
                if (body != null) {
                    // JAudioTagger's FrameBodySYLT provides access to the synchronized text
                    // This is a bit complex in JAudioTagger, sometimes it's easier to parse raw if high-level fails
                    // For now, if we have a SYLT frame, we know it's synced.
                    // If JAudioTagger doesn't provide a clean string, we might need our manual parser as fallback.
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun isSynced(text: String): Boolean {
        return text.contains(Regex("\\[\\d+:\\d+[.:]\\d+\\]"))
    }
}
