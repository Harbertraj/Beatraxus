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

object RingtoneHelper {

    sealed class Result {
        object Success : Result()
        object NeedsWriteSettings : Result()
        data class Error(val message: String) : Result()
    }

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

    /** Decodes just the chosen part and writes it as an MP3 (plays on every Android version as a tone). */
    private fun trimToFile(context: Context, song: Song, startMs: Long, lengthMs: Long, fade: Boolean, out: File): Boolean {
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
        args += listOf("-c:a", "libmp3lame", "-q:a", "2", out.absolutePath)
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
