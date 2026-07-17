package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.microsoft.graph.models.DriveItem
import com.microsoft.graph.requests.GraphServiceClient
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request

import com.microsoft.graph.models.DriveItemSearchParameterSet

class OneDriveLibraryScanner(private val context: Context) {

    fun scanAccountFlow(graphClient: GraphServiceClient<Request>, accountEmail: String, allowedFormats: Set<String>? = null): Flow<List<Song>> = flow<List<Song>> {
        try {
            val searchParams = DriveItemSearchParameterSet.newBuilder().withQ("").build()
            var result = graphClient.me().drive().root().search(searchParams).buildRequest().get()
            
            while (result != null) {
                val pageSongs = result.currentPage
                    .filter { it.audio != null || CloudScanConstants.isSupportedAudioFile(it.name ?: "", it.file?.mimeType, allowedFormats) }
                    .map { it.toSong(accountEmail) }

                if (pageSongs.isNotEmpty()) {
                    emit(pageSongs)
                }
                
                val nextPage = result.getNextPage()
                result = nextPage?.buildRequest()?.get()
            }
        } catch (e: Exception) {
            Log.e("OneDriveScanner", "OneDrive scan error: ${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    private fun DriveItem.toSong(accountEmail: String): Song {
        val ext = name?.substringAfterLast('.', "")?.uppercase() ?: "AUDIO"
        val title = name?.substringBeforeLast('.') ?: "Unknown"
        
        return Song(
            id = id ?: "",
            title = title,
            artist = audio?.artist ?: "Unknown Artist",
            album = audio?.album ?: "Unknown Album",
            uri = Uri.parse("https://graph.microsoft.com/v1.0/me/drive/items/$id/content"),
            durationMs = audio?.duration?.toLong() ?: 0L,
            format = ext,
            sampleRateHz = 0,
            fileSizeBytes = size ?: 0L,
            dateAdded = lastModifiedDateTime?.toInstant()?.toEpochMilli() ?: 0L,
            source = SongSource.ONEDRIVE,
            folder = parentReference?.path?.substringAfterLast('/') ?: "ONEDRIVE",
            onedriveFileId = id,
            onedriveAccountEmail = accountEmail.lowercase()
        )
    }
}
