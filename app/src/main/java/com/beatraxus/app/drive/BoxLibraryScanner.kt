package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.box.androidsdk.content.BoxApiFile
import com.box.androidsdk.content.BoxApiSearch
import com.box.androidsdk.content.models.BoxFile
import com.box.androidsdk.content.models.BoxSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

import com.box.androidsdk.content.BoxApiFolder
import com.box.androidsdk.content.models.BoxFolder

class BoxLibraryScanner(private val context: Context) {

    fun scanAccountFlow(session: BoxSession, accountEmail: String, allowedFormats: Set<String>? = null): Flow<List<Song>> = flow {
        try {
            val folderApi = BoxApiFolder(session)
            emitAll(scanFolderRecursive("0", folderApi, accountEmail, allowedFormats))
        } catch (e: Exception) {
            Log.e("BoxScanner", "Box scan error: ${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    private fun scanFolderRecursive(folderId: String, folderApi: BoxApiFolder, accountEmail: String, allowedFormats: Set<String>?): Flow<List<Song>> = flow {
        val songs = mutableListOf<Song>()
        try {
            val folderItems = folderApi.getItemsRequest(folderId)
                .setFields("id", "name", "size", "modified_at", "parent", "type")
                .send()
            
            folderItems.forEach { item ->
                if (item is BoxFile) {
                    if (CloudScanConstants.isSupportedAudioFile(item.name ?: "", null, allowedFormats)) {
                        songs.add(item.toSong(accountEmail))
                    }
                } else if (item is BoxFolder) {
                    emitAll(scanFolderRecursive(item.id, folderApi, accountEmail, allowedFormats))
                }
            }
        } catch (e: Exception) {
            Log.w("BoxScanner", "Failed to scan folder $folderId: ${e.message}")
        }
        if (songs.isNotEmpty()) emit(songs)
    }

    private fun BoxFile.toSong(accountEmail: String): Song {
        val ext = name?.substringAfterLast('.', "")?.uppercase() ?: "AUDIO"
        val title = name?.substringBeforeLast('.') ?: "Unknown"
        
        return Song(
            id = id ?: "",
            title = title,
            artist = "Unknown Artist",
            album = "Unknown Album",
            uri = Uri.parse("https://api.box.com/2.0/files/$id/content"),
            durationMs = 0L,
            format = ext,
            sampleRateHz = 0,
            fileSizeBytes = size ?: 0L,
            dateAdded = modifiedAt?.time ?: 0L,
            source = SongSource.BOX,
            folder = parent?.name ?: "BOX",
            boxFileId = id,
            boxAccountEmail = accountEmail.lowercase()
        )
    }
}
