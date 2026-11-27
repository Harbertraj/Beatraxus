package com.beatraxus.app.features

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.runtime.Composable
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.beatraxus.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** File formats a cut can be exported as (needs the full ffmpeg-kit build, which Beatraxus already bundles). */
enum class ExportFormat(val label: String, val ext: String, val mime: String, val codec: List<String>) {
    MP3("MP3", "mp3", "audio/mpeg", listOf("-c:a", "libmp3lame", "-q:a", "0")),
    FLAC("FLAC", "flac", "audio/flac", listOf("-c:a", "flac")),
    M4A("M4A", "m4a", "audio/mp4", listOf("-c:a", "aac", "-b:a", "256k")),
    WAV("WAV", "wav", "audio/x-wav", listOf("-c:a", "pcm_s16le")),
    OGG("OGG", "ogg", "audio/ogg", listOf("-c:a", "libvorbis", "-q:a", "6"))
}

object RingtoneHelper {

    sealed class Result {
        object Success : Result()
        object NeedsWriteSettings : Result()
        data class Error(val message: String) : Result()
    }

    sealed class SaveResult {
        data class Saved(val fileName: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    /** Folder (under Music) that exported cuts are saved to. */
    const val EXPORT_FOLDER = "Beatraxus"

    fun openWriteSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Uses the WHOLE song (a straight byte copy, no re-encode, so no quality loss).
     * [type] is RingtoneManager.TYPE_RINGTONE, TYPE_NOTIFICATION or TYPE_ALARM.
     */
    suspend fun setAs(context: Context, song: Song, type: Int): Result = withContext(Dispatchers.IO) {
        if (song.isCloud()) return@withContext Result.Error("Download the song first. Cloud songs can't be used as a ringtone.")
        if (!Settings.System.canWrite(context)) return@withContext Result.NeedsWriteSettings
        try {
            val target = copyToRingtoneStore(context, song, type)
                ?: return@withContext Result.Error("Could not copy the song to the ringtone folder.")
            RingtoneManager.setActualDefaultRingtoneUri(context, type, target)
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to set ringtone")
        }
    }

    /**
     * Cuts [lengthMs] of the song starting at [startMs] (optionally with a short fade in and fade out),
     * saves the clip to the ringtone store and sets it. The original song is never touched.
     */
    suspend fun setClipAs(
        context: Context,
        song: Song,
        startMs: Long,
        lengthMs: Long,
        fade: Boolean,
        type: Int
    ): Result = withContext(Dispatchers.IO) {
        if (song.isCloud()) return@withContext Result.Error("Download the song first. Cloud songs can't be used as a ringtone.")
        if (!Settings.System.canWrite(context)) return@withContext Result.NeedsWriteSettings
        if (lengthMs < 1000L) return@withContext Result.Error("Pick at least one second.")
        val clip = File(context.cacheDir, "ringtone_clip_${System.nanoTime()}.mp3")
        try {
            val name = clipName(song, startMs, lengthMs, fade, type)
            var target: Uri? = findExisting(context, name)
            if (target == null) {
                if (!trimToFile(context, song, startMs, lengthMs, fade, clip)) {
                    return@withContext Result.Error("Could not cut this song. Try another part.")
                }
                target = insertIntoStore(context, name, "${song.title} (cut)", song.artist, "audio/mpeg", type) { out ->
                    clip.inputStream().use { it.copyTo(out) }
                    true
                }
            }
            if (target == null) return@withContext Result.Error("Could not save the cut to the ringtone folder.")
            RingtoneManager.setActualDefaultRingtoneUri(context, type, target)
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to set ringtone")
        } finally {
            clip.delete()
        }
    }

    /**
     * Cuts [lengthMs] of the song starting at [startMs] (optionally faded) and saves it as a normal audio file in
     * Music/Beatraxus in the chosen [format]. This does not touch the ringtone settings or the original song.
     */
    suspend fun saveClip(
        context: Context,
        song: Song,
        startMs: Long,
        lengthMs: Long,
        fade: Boolean,
        format: ExportFormat
    ): SaveResult = withContext(Dispatchers.IO) {
        if (song.isCloud()) return@withContext SaveResult.Error("Download the song first. Cloud songs can't be cut.")
        if (lengthMs < 1000L) return@withContext SaveResult.Error("Pick at least one second.")
        val tmp = File(context.cacheDir, "cut_export_${System.nanoTime()}.${format.ext}")
        try {
            val ok = trimToFile(
                context, song, startMs, lengthMs, fade, tmp,
                codec = format.codec, title = "${song.title} (cut)", artist = song.artist
            )
            if (!ok) return@withContext SaveResult.Error("Could not cut this song. Try another part or format.")
            val name = exportName(song, startMs, lengthMs, format)
            val uri = insertIntoMusic(context, name, "${song.title} (cut)", song.artist, format.mime) { out ->
                tmp.inputStream().use { it.copyTo(out) }
                true
            } ?: return@withContext SaveResult.Error("Could not save the file to your Music folder.")
            SaveResult.Saved(name)
        } catch (e: Exception) {
            SaveResult.Error(e.message ?: "Failed to save the cut")
        } finally {
            tmp.delete()
        }
    }

    private fun exportName(song: Song, startMs: Long, lengthMs: Long, format: ExportFormat): String {
        // Keeps non-Latin titles (e.g. Tamil), only strips characters that are not allowed in file names.
        val title = song.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().take(80).ifBlank { "song" }
        fun t(ms: Long): String {
            val s = ms / 1000L
            val frac = ms % 1000L
            val base = "${s / 60}m${(s % 60).toString().padStart(2, '0')}"
            return if (frac == 0L) "${base}s" else "$base.${frac.toString().padStart(3, '0')}s"
        }
        return "$title (cut ${t(startMs)}-${t(startMs + lengthMs)}).${format.ext}"
    }

    /** Creates the file in Music/Beatraxus (visible in the gallery/music apps) and lets [write] fill it. */
    private fun insertIntoMusic(
        context: Context,
        name: String,
        title: String,
        artist: String,
        mime: String,
        write: (OutputStream) -> Boolean
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_MUSIC, true)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$EXPORT_FOLDER")
            values.put(MediaStore.Audio.Media.IS_PENDING, 1)
            val newUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val ok = try {
                resolver.openOutputStream(newUri)?.use { write(it) } ?: false
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                runCatching { resolver.delete(newUri, null, null) }
                return null
            }
            resolver.update(newUri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            newUri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), EXPORT_FOLDER).apply { mkdirs() }
            val file = File(dir, name)
            val ok = file.outputStream().use { write(it) }
            if (!ok) {
                file.delete()
                return null
            }
            @Suppress("DEPRECATION")
            values.put(MediaStore.Audio.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Audio.Media.getContentUriForPath(file.absolutePath)!!, values)
        }
    }

    private fun clipName(song: Song, startMs: Long, lengthMs: Long, fade: Boolean, type: Int): String {
        val kind = when (type) {
            RingtoneManager.TYPE_NOTIFICATION -> "n"
            RingtoneManager.TYPE_ALARM -> "a"
            else -> "r"
        }
        return "Beatraxus_${safeTitle(song)}_${song.id.hashCode().toUInt()}_${startMs}-${lengthMs}${if (fade) "f" else ""}_$kind.mp3"
    }

    private fun safeTitle(song: Song): String =
        song.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "song" }

    private fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

    /**
     * Decodes just the chosen part and encodes it with [codec] (default: MP3, which plays on every Android version
     * as a tone). [title] and [artist], when given, are written as tags; other metadata is dropped.
     */
    private fun trimToFile(
        context: Context,
        song: Song,
        startMs: Long,
        lengthMs: Long,
        fade: Boolean,
        out: File,
        codec: List<String> = listOf("-c:a", "libmp3lame", "-q:a", "2"),
        title: String? = null,
        artist: String? = null
    ): Boolean {
        val input = FFmpegKitConfig.getSafParameterForRead(context, song.uri)
        val args = mutableListOf(
            "-y", "-v", "error",
            "-ss", sec(startMs), "-t", sec(lengthMs),
            "-i", input,
            "-vn", "-map", "0:a:0", "-map_metadata", "-1"
        )
        if (fade && lengthMs >= 4000L) {
            args += listOf("-af", "afade=t=in:st=0:d=1.5,afade=t=out:st=${sec(lengthMs - 1500L)}:d=1.5")
        }
        if (title != null) args += listOf("-metadata", "title=$title")
        if (artist != null) args += listOf("-metadata", "artist=$artist")
        args += codec
        args += out.absolutePath
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        return ReturnCode.isSuccess(session.returnCode) && out.length() > 0L
    }

    private fun copyToRingtoneStore(context: Context, song: Song, type: Int): Uri? {
        val ext = song.format.lowercase().removePrefix(".").ifBlank { "mp3" }
        val name = "Beatraxus_${safeTitle(song)}_${song.id.hashCode().toUInt()}.$ext"
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/*"
        }

        // Reuse an earlier copy
        findExisting(context, name)?.let { return it }

        return insertIntoStore(context, name, song.title, song.artist, mime, type) { out ->
            val input = context.contentResolver.openInputStream(song.uri) ?: return@insertIntoStore false
            input.use { it.copyTo(out) }
            true
        }
    }

    private fun findExisting(context: Context, name: String): Uri? {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME}=?", arrayOf(name), null
        )?.use { c ->
            if (c.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }
        return null
    }

    /** Creates the MediaStore entry in the right folder and lets [write] fill it. Returns null on failure. */
    private fun insertIntoStore(
        context: Context,
        name: String,
        title: String,
        artist: String,
        mime: String,
        type: Int,
        write: (OutputStream) -> Boolean
    ): Uri? {
        val folder = when (type) {
            RingtoneManager.TYPE_NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
            RingtoneManager.TYPE_ALARM -> Environment.DIRECTORY_ALARMS
            else -> Environment.DIRECTORY_RINGTONES
        }
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            val newUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val ok = resolver.openOutputStream(newUri)?.use { write(it) } ?: false
            if (!ok) {
                runCatching { resolver.delete(newUri, null, null) }
                return null
            }
            newUri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(folder).apply { mkdirs() }
            val file = File(dir, name)
            val ok = file.outputStream().use { write(it) }
            if (!ok) {
                file.delete()
                return null
            }
            @Suppress("DEPRECATION")
            values.put(MediaStore.Audio.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Audio.Media.getContentUriForPath(file.absolutePath)!!, values)
        }
    }
}

/**
 * "Set as" screen. Opens the ringtone cutter: pick the part of the song, preview it, then set it as the
 * ringtone, notification or alarm sound. [onPauseLocalPlayback] stops the app's own player when a preview starts.
 */
@Composable
fun RingtoneDialog(song: Song, onDismiss: () -> Unit, onPauseLocalPlayback: () -> Unit = {}) {
    RingtoneCutterScreen(song = song, onDismiss = onDismiss, onPauseLocalPlayback = onPauseLocalPlayback)
}
