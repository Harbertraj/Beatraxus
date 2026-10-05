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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.beatraxus.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /** [type] is RingtoneManager.TYPE_RINGTONE, TYPE_NOTIFICATION or TYPE_ALARM. */
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

    private fun copyToRingtoneStore(context: Context, song: Song, type: Int): Uri? {
        val ext = song.format.lowercase().removePrefix(".").ifBlank { "mp3" }
        val safeTitle = song.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "song" }
        val name = "Beatraxus_${safeTitle}_${song.id.hashCode().toUInt()}.$ext"
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/*"
        }
        val folder = when (type) {
            RingtoneManager.TYPE_NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
            RingtoneManager.TYPE_ALARM -> Environment.DIRECTORY_ALARMS
            else -> Environment.DIRECTORY_RINGTONES
        }
        val resolver = context.contentResolver

        // Reuse an earlier copy
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME}=?", arrayOf(name), null
        )?.use { c ->
            if (c.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            val newUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openInputStream(song.uri)?.use { input ->
                resolver.openOutputStream(newUri)?.use { out -> input.copyTo(out) }
            } ?: return null
            newUri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(folder).apply { mkdirs() }
            val file = File(dir, name)
            resolver.openInputStream(song.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
            @Suppress("DEPRECATION")
            values.put(MediaStore.Audio.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Audio.Media.getContentUriForPath(file.absolutePath)!!, values)
        }
    }
}

/** Dialog: choose Ringtone / Notification / Alarm, handles the WRITE_SETTINGS permission. */
@Composable
fun RingtoneDialog(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun apply(type: Int) {
        busy = true
        scope.launch {
            when (val r = RingtoneHelper.setAs(context, song, type)) {
                is RingtoneHelper.Result.Success -> {
                    Toast.makeText(context, "Set \"${song.title}\" successfully", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                is RingtoneHelper.Result.NeedsWriteSettings -> {
                    Toast.makeText(context, "Allow \"Modify system settings\" for Beatraxus, then try again", Toast.LENGTH_LONG).show()
                    RingtoneHelper.openWriteSettings(context)
                    onDismiss()
                }
                is RingtoneHelper.Result.Error -> {
                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                    busy = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Set as…") },
        text = { Text(if (busy) "Working…" else "\"${song.title}\"") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { apply(RingtoneManager.TYPE_RINGTONE) }) { Text("Ringtone") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(enabled = !busy, onClick = { apply(RingtoneManager.TYPE_NOTIFICATION) }) { Text("Notification") }
                TextButton(enabled = !busy, onClick = { apply(RingtoneManager.TYPE_ALARM) }) { Text("Alarm") }
            }
        }
    )
}
