package com.beatraxus.app.subtitles.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.beatraxus.app.subtitles.domain.FontFileSniffer
import com.beatraxus.app.subtitles.domain.SubtitleFontError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A font the viewer picked for subtitles (video feature 23). */
data class SubtitleFont(val displayName: String, val typeface: Typeface)

/**
 * Keeps the one custom subtitle font: a copy of the picked TTF/OTF in the app's private storage, so the font still
 * works after the original file is moved or its access permission ends. Picking a new font replaces the old one.
 */
class SubtitleFontStore(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private val fontDir: File
        get() = File(context.filesDir, "subtitle_fonts").apply { if (!exists()) mkdirs() }

    /** The saved font, or null when none is set or its file is gone or no longer loads. */
    suspend fun load(): SubtitleFont? = withContext(Dispatchers.IO) {
        val fileName = prefs.getString(KEY_FILE, null) ?: return@withContext null
        val file = File(fontDir, fileName)
        if (!file.isFile) return@withContext null
        val typeface = try {
            Typeface.createFromFile(file)
        } catch (_: Exception) {
            return@withContext null
        }
        SubtitleFont(prefs.getString(KEY_NAME, null) ?: FontFileSniffer.displayLabel(fileName), typeface)
    }

    /** Copies the font at [uri] into the app, makes it the subtitle font and returns it. */
    suspend fun import(uri: Uri): Result<SubtitleFont> = withContext(Dispatchers.IO) {
        try {
            val bytes = readLimited(uri)
                ?: return@withContext Result.failure(FontImportException(SubtitleFontError.TOO_LARGE))
            val kind = FontFileSniffer.detect(bytes)
                ?: return@withContext Result.failure(FontImportException(SubtitleFontError.NOT_A_FONT))

            val dir = fontDir
            val file = File(dir, FontFileSniffer.storedFileName(kind, System.currentTimeMillis()))
            file.writeBytes(bytes)

            val typeface = try {
                Typeface.createFromFile(file)
            } catch (_: Exception) {
                file.delete()
                return@withContext Result.failure(FontImportException(SubtitleFontError.UNUSABLE))
            }

            val displayName = FontFileSniffer.displayLabel(displayNameOf(uri))
            prefs.edit().putString(KEY_FILE, file.name).putString(KEY_NAME, displayName).apply()
            deleteOtherFonts(dir, keep = file.name)
            Result.success(SubtitleFont(displayName, typeface))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(FontImportException(SubtitleFontError.UNREADABLE, e))
        }
    }

    /** Goes back to the default subtitle font and deletes the copy. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_FILE).remove(KEY_NAME).apply()
        deleteOtherFonts(fontDir, keep = null)
    }

    /** File contents, or null when the file is larger than [FontFileSniffer.MAX_FONT_BYTES]. */
    private fun readLimited(uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > FontFileSniffer.MAX_FONT_BYTES) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun displayNameOf(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun deleteOtherFonts(dir: File, keep: String?) {
        dir.listFiles()?.forEach { f ->
            if (f.name != keep && FontFileSniffer.isStoredFontName(f.name)) f.delete()
        }
    }

    class FontImportException(val error: SubtitleFontError, cause: Throwable? = null) : Exception(error.name, cause)

    private companion object {
        const val KEY_FILE = "video_subtitle_font_file"
        const val KEY_NAME = "video_subtitle_font_name"
    }
}
