package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class NextcloudLibraryScanner(private val context: Context) {

    fun scanAccountFlow(serverUrl: String, username: String, password: String, allowedFormats: Set<String>? = null): Flow<List<Song>> = flow<List<Song>> {
        try {
            val sardine = OkHttpSardine()
            sardine.setCredentials(username, password)
            
            emitAll(scanRecursive(serverUrl, sardine, serverUrl, username, allowedFormats))
        } catch (e: Exception) {
            Log.e("NextcloudScanner", "Nextcloud scan error: ${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    private fun scanRecursive(rootUrl: String, sardine: Sardine, currentUrl: String, username: String, allowedFormats: Set<String>?): Flow<List<Song>> = flow {
        val resources = sardine.list(currentUrl)
        val songs = mutableListOf<Song>()
        
        resources.forEach { res ->
            if (res.isDirectory) {
                val folderUrl = res.href.toString()
                if (folderUrl != currentUrl && folderUrl != "$currentUrl/") {
                    emitAll(scanRecursive(rootUrl, sardine, folderUrl, username, allowedFormats))
                }
            } else {
                if (CloudScanConstants.isSupportedAudioFile(res.name, res.contentType, allowedFormats)) {
                    songs.add(res.toSong(rootUrl, username))
                }
            }
        }
        if (songs.isNotEmpty()) emit(songs)
    }

    private fun DavResource.toSong(rootUrl: String, username: String): Song {
        val ext = name.substringAfterLast('.', "").uppercase()
        val title = name.substringBeforeLast('.')
        
        return Song(
            id = href.toString(),
            title = title,
            artist = "Unknown Artist",
            album = "Unknown Album",
            uri = Uri.parse(href.toString()),
            durationMs = 0L,
            format = ext.ifEmpty { "AUDIO" },
            sampleRateHz = 0,
            fileSizeBytes = contentLength ?: 0L,
            dateAdded = modified?.time ?: 0L,
            source = SongSource.NEXTCLOUD,
            folder = href.toString().substringBeforeLast('/', "NEXTCLOUD"),
            nextcloudFileId = href.toString(),
            nextcloudAccountEmail = username
        )
    }
}
