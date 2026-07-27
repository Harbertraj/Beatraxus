package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.Metadata
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class DropboxLibraryScanner(private val context: Context) {

    fun scanAccountFlow(accessToken: String, accountEmail: String, allowedFormats: Set<String>? = null): Flow<List<Song>> = flow<List<Song>> {
        try {
            val client = buildDropboxClient(accessToken)
            var result = client.files().listFolderBuilder("").withRecursive(true).start()

            while (true) {
                val pageSongs = result.entries
                    .filterIsInstance<FileMetadata>()
                    .filter { CloudScanConstants.isSupportedAudioFile(it.name, null, allowedFormats) }
                    .map { it.toSong(accountEmail) }

                if (pageSongs.isNotEmpty()) {
                    emit(pageSongs)
                }

                if (!result.hasMore) break
                result = client.files().listFolderContinue(result.cursor)
            }
        } catch (e: Exception) {
            Log.e("DropboxScanner", "Dropbox scan error: ${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    private fun buildDropboxClient(accessToken: String): DbxClientV2 {
        val config = DbxRequestConfig.newBuilder("Beatraxus").build()
        return DbxClientV2(config, accessToken)
    }

    private fun FileMetadata.toSong(accountEmail: String): Song {
        val ext = name.substringAfterLast('.', "").uppercase()
        val title = name.substringBeforeLast('.')
        
        return Song(
            id = id,
            title = title,
            artist = "Unknown Artist",
            album = "Unknown Album",
            uri = Uri.parse("https://content.dropboxapi.com/2/files/download"),
            durationMs = 0L,
            format = ext.ifEmpty { "AUDIO" },
            sampleRateHz = 0,
            fileSizeBytes = size,
            dateAdded = serverModified.time,
            source = SongSource.DROPBOX,
            folder = pathDisplay?.substringBeforeLast('/', "DROPBOX") ?: "DROPBOX",
            dropboxFileId = id,
            dropboxAccountEmail = accountEmail.lowercase()
        )
    }
}
