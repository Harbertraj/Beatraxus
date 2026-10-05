package com.beatraxus.app.features

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beatraxus.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

data class TagData(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val trackNumber: String,
    val discNumber: String,
    val composer: String
) {
    companion object {
        fun from(song: Song) = TagData(
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumArtist = song.albumArtist.orEmpty(),
            genre = if (song.genre == "Unknown") "" else song.genre,
            year = if (song.year > 0) song.year.toString() else "",
            trackNumber = song.trackNumber?.toString().orEmpty(),
            discNumber = song.discNumber?.toString().orEmpty(),
            composer = song.composer.orEmpty()
        )
    }
}

/** Applies edited tags to an in-memory Song (used to update the library after saving). */
fun Song.withTags(t: TagData): Song = copy(
    title = t.title.ifBlank { title },
    artist = t.artist.ifBlank { artist },
    album = t.album.ifBlank { album },
    albumArtist = t.albumArtist.ifBlank { null },
    genre = t.genre.ifBlank { "Unknown" },
    year = t.year.toIntOrNull() ?: 0,
    trackNumber = t.trackNumber.toIntOrNull(),
    discNumber = t.discNumber.toIntOrNull(),
    composer = t.composer.ifBlank { null }
)

object TagEditor {

    sealed class Result {
        object Success : Result()
        data class NeedsPermission(val intentSender: IntentSender) : Result()
        data class Error(val message: String) : Result()
    }

    fun canEdit(song: Song): Boolean =
        !song.isCloud() && (song.uri.scheme == "content" || song.uri.scheme == "file") &&
            song.source == com.beatraxus.app.model.SongSource.LOCAL

    /** Writes tags into the audio file. If Android needs the user's consent, returns NeedsPermission. */
    suspend fun write(context: Context, song: Song, tags: TagData): Result = withContext(Dispatchers.IO) {
        if (!canEdit(song)) return@withContext Result.Error("Only songs stored on this device can be edited.")
        val resolver = context.contentResolver
        val ext = song.format.lowercase().removePrefix(".").ifBlank { "mp3" }
        val temp = File(context.cacheDir, "tag_edit_${song.id.hashCode()}.$ext")
        try {
            // 1) Make sure we may write to the file before doing any work
            if (song.uri.scheme == "content") {
                try {
                    resolver.openFileDescriptor(song.uri, "rw")?.close()
                } catch (e: SecurityException) {
                    return@withContext permissionRequest(context, song.uri, e)
                        ?: Result.Error("No permission to edit this file.")
                }
            }

            // 2) Copy to a temp file, edit with jaudiotagger
            resolver.openInputStream(song.uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: return@withContext Result.Error("Could not read the file.")

            val audioFile = AudioFileIO.read(temp)
            val tag = audioFile.tagOrCreateAndSetDefault
            fun set(key: FieldKey, value: String) {
                if (value.isBlank()) {
                    try { tag.deleteField(key) } catch (_: Exception) {}
                } else {
                    tag.setField(key, value.trim())
                }
            }
            set(FieldKey.TITLE, tags.title)
            set(FieldKey.ARTIST, tags.artist)
            set(FieldKey.ALBUM, tags.album)
            set(FieldKey.ALBUM_ARTIST, tags.albumArtist)
            set(FieldKey.GENRE, tags.genre)
            set(FieldKey.YEAR, tags.year)
            set(FieldKey.TRACK, tags.trackNumber)
            set(FieldKey.DISC_NO, tags.discNumber)
            set(FieldKey.COMPOSER, tags.composer)
            audioFile.commit()

            // 3) Write back into the original file
            val out = resolver.openOutputStream(song.uri, "wt")
                ?: return@withContext Result.Error("Could not open the file for writing.")
            out.use { o -> temp.inputStream().use { it.copyTo(o) } }

            // 4) Refresh MediaStore row so other apps see the change
            if (song.uri.scheme == "content") {
                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, tags.title.ifBlank { song.title })
                        put(MediaStore.Audio.Media.ARTIST, tags.artist.ifBlank { song.artist })
                        put(MediaStore.Audio.Media.ALBUM, tags.album.ifBlank { song.album })
                    }
                    resolver.update(song.uri, v, null, null)
                } catch (_: Exception) { /* not critical */ }
            }
            Result.Success
        } catch (e: SecurityException) {
            permissionRequest(context, song.uri, e) ?: Result.Error("No permission to edit this file.")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to write tags")
        } finally {
            temp.delete()
        }
    }

    private fun permissionRequest(context: Context, uri: Uri, e: SecurityException): Result.NeedsPermission? {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    Result.NeedsPermission(MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    Result.NeedsPermission(e.userAction.actionIntent.intentSender)
                else -> null
            }
        } catch (_: Exception) { null }
    }
}

@Composable
fun TagEditorDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSaved: (Song, TagData) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf(TagData.from(song)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Retry after the user grants write access
    var pending by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) pending = true else { busy = false; error = "Permission denied" }
    }

    fun doWrite() {
        busy = true
        error = null
        scope.launch {
            when (val r = TagEditor.write(context, song, tags)) {
                is TagEditor.Result.Success -> {
                    Toast.makeText(context, "Tags saved", Toast.LENGTH_SHORT).show()
                    onSaved(song, tags)
                    onDismiss()
                }
                is TagEditor.Result.NeedsPermission -> {
                    permissionLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build())
                }
                is TagEditor.Result.Error -> { busy = false; error = r.message }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending) {
            pending = false
            doWrite()
        }
    }

    @Composable
    fun field(label: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth()
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit tags") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                field("Title", tags.title) { tags = tags.copy(title = it) }
                field("Artist", tags.artist) { tags = tags.copy(artist = it) }
                field("Album", tags.album) { tags = tags.copy(album = it) }
                field("Album artist", tags.albumArtist) { tags = tags.copy(albumArtist = it) }
                field("Genre", tags.genre) { tags = tags.copy(genre = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { field("Year", tags.year, true) { tags = tags.copy(year = it.filter(Char::isDigit).take(4)) } }
                    Column(Modifier.weight(1f)) { field("Track", tags.trackNumber, true) { tags = tags.copy(trackNumber = it.filter(Char::isDigit).take(3)) } }
                    Column(Modifier.weight(1f)) { field("Disc", tags.discNumber, true) { tags = tags.copy(discNumber = it.filter(Char::isDigit).take(2)) } }
                }
                field("Composer", tags.composer) { tags = tags.copy(composer = it) }
                error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF5252)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && tags.title.isNotBlank(), onClick = { doWrite() }) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Small helper so other classes can apply edited tags to a Song. */
object TagEditorHelper {
    fun Song.withEditedTags(t: TagData): Song = this.withTags(t)
}
