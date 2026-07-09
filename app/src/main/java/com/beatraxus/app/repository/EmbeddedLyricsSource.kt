package com.beatraxus.app.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
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

class EmbeddedLyricsSource(private val context: Context) {
    private val TAG = "EmbeddedLyricsSource"

    suspend fun saveLyrics(songPath: String, lyrics: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(songPath)
        if (!file.exists() || !file.canWrite()) return@withContext false
        
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: audioFile.createDefaultTag()
            
            tag.setField(FieldKey.LYRICS, lyrics)
            audioFile.commit()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun saveLyrics(uri: Uri, lyrics: String): Boolean = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext saveLyrics(uri.path ?: return@withContext false, lyrics)
        }
        
        if (uri.scheme == "content") {
            val realPath = getRealPathFromURI(uri)
            if (realPath != null) {
                return@withContext saveLyrics(realPath, lyrics)
            }
        }
        false
    }

    suspend fun getLyrics(songPath: String): LyricsResult? = withContext(Dispatchers.IO) {
        val file = File(songPath)
        if (!file.exists()) return@withContext null
        
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return@withContext null

            // 1. Try SYLT (Synchronized Lyrics)
            val syltContent = extractSylt(tag)
            if (syltContent != null) return@withContext LyricsResult(LyricsType.SYNCED, syltContent)

            // 2. Try USLT (Unsynchronized Lyrics) using multiple keys
            val lyrics = tag.getFirst(FieldKey.LYRICS).ifBlank {
                tag.getFirst(FieldKey.CUSTOM1).ifBlank { // Some players use CUSTOM1
                    // For FLAC/Vorbis, it might be under a different name
                    tag.getFirst("LYRICS") 
                }
            }

            if (lyrics.isNotBlank()) {
                return@withContext LyricsResult(
                    type = if (isSynced(lyrics)) LyricsType.SYNCED else LyricsType.PLAIN,
                    content = lyrics.trim()
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
        
        if (uri.scheme == "content") {
            val realPath = getRealPathFromURI(uri)
            if (realPath != null) {
                return@withContext getLyrics(realPath)
            }
        }
        
        // Fallback for content URIs or cloud URIs using MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            // METADATA_KEY_LYRIC is 23 (added in API 31)
            val lyrics = retriever.extractMetadata(23)
            if (!lyrics.isNullOrBlank()) {
                return@withContext LyricsResult(
                    type = if (isSynced(lyrics)) LyricsType.SYNCED else LyricsType.PLAIN,
                    content = lyrics.trim()
                )
            }
        } catch (e: Exception) {
            // Silently fail
        }
        null
    }

    private fun getRealPathFromURI(contentUri: Uri): String? {
        val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
        return try {
            context.contentResolver.query(contentUri, projection, null, null, null)?.use { cursor ->
                val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                if (cursor.moveToFirst()) cursor.getString(columnIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractSylt(tag: org.jaudiotagger.tag.Tag): String? {
        // JAudioTagger SYLT support is limited in high-level API.
        // We look for SYLT frames in ID3 tags.
        try {
            if (tag is org.jaudiotagger.tag.id3.AbstractID3v2Tag) {
                val fields = tag.getFields("SYLT")
                if (fields.isEmpty()) return null
                
                // For simplicity, we just return the first one if it has content.
                // Full SYLT parsing into LRC format is complex, but often the 
                // raw bytes contain enough for us to detect it's synced.
                // If it's tagged as SYLT, we might need a more specialized parser.
                // For now, let's assume if it exists, we'll try to find USLT first
                // as it's more standard for LRC content.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract SYLT from tag", e)
        }
        return null
    }

    private fun isSynced(text: String): Boolean {
        // [mm:ss], [mm:ss.xx], [mm:ss.xxx], [h:mm:ss]
        return text.contains(Regex("\\[\\d+:\\d{2}(?:[.:]\\d+)?\\]"))
    }
}
