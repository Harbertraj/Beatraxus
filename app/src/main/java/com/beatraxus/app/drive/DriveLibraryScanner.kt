package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class DriveLibraryScanner(private val context: Context) {

    companion object {
        fun buildDriveQuery(): String {
            return "mimeType contains 'audio/' and trashed = false"
        }

        fun isSupportedAudioFile(filename: String, mimeType: String? = null, allowedFormats: Set<String>? = null): Boolean {
            return CloudScanConstants.isSupportedAudioFile(filename, mimeType, allowedFormats)
        }
    }

    fun scanAccountFlow(credential: GoogleAccountCredential, allowedFormats: Set<String>? = null): Flow<List<Song>> = flow {
        try {
            val driveService = buildDriveService(credential)
            val query = buildDriveQuery()
            var pageToken: String? = null

            do {
                val result = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, owners, thumbnailLink)")
                    .setPageSize(1000)
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                    .execute()

                val pageSongs = result.files
                    ?.filter { isSupportedAudioFile(it.name, it.mimeType, allowedFormats) }
                    ?.map { file -> file.toSong(credential.selectedAccountName) }
                    ?: emptyList()

                if (pageSongs.isNotEmpty()) {
                    emit(pageSongs)
                }

                pageToken = result.nextPageToken
            } while (pageToken != null && currentCoroutineContext().isActive)

        } catch (e: UserRecoverableAuthIOException) {
            DrivePlaybackHelper.authRecoveryFlow.tryEmit(e.intent)
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DrivePlaybackHelper.errorState.tryEmit("Drive scan error: ${e.message}")
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun scanAccount(credential: GoogleAccountCredential, allowedFormats: Set<String>? = null): List<Song> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            try {
                Log.d("DriveScanner", "Starting scan for account: ${credential.selectedAccountName}")
                val driveService = buildDriveService(credential)
                val query = buildDriveQuery()
                Log.d("DriveScanner", "Query: $query")
                var pageToken: String? = null

                while (true) {
                    val result = driveService.files().list()
                        .setQ(query)
                        .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, owners, thumbnailLink)")
                        .setPageSize(1000)
                        .apply { if (pageToken != null) setPageToken(pageToken) }
                        .execute()

                    val pageSongs = result.files
                        ?.filter { isSupportedAudioFile(it.name, it.mimeType, allowedFormats) }
                        ?.map { file -> file.toSong(credential.selectedAccountName) }
                        ?: emptyList()

                    Log.d("DriveScanner", "Found ${pageSongs.size} songs in this page")
                    songs.addAll(pageSongs)
                    pageToken = result.nextPageToken
                    if (pageToken == null) break
                }
                Log.d("DriveScanner", "Scan complete. Total songs: ${songs.size}")
            } catch (e: UserRecoverableAuthIOException) {
                Log.e("DriveScanner", "User recoverable auth error", e)
                DrivePlaybackHelper.authRecoveryFlow.tryEmit(e.intent)
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("DriveScanner", "Drive scan fatal error: ${e.message}", e)
                throw e
            }
            songs
        }

    private fun buildDriveService(credential: GoogleAccountCredential): Drive {
        val transport = com.google.api.client.http.javanet.NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        return Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName("Beatraxus")
            .build()
    }

    private fun com.google.api.services.drive.model.File.toSong(accountEmail: String?): Song {
        val ext = name.substringAfterLast('.', "").uppercase()
        val title = name.substringBeforeLast('.')
        
        val formatLabel = ext.ifEmpty { "AUDIO" }
        
        val artUri = if (!thumbnailLink.isNullOrBlank()) {
            val highResThumb = if (thumbnailLink.contains("=s220")) {
                thumbnailLink.replace("=s220", "=s500")
            } else thumbnailLink
            Uri.parse(highResThumb)
        } else null

        return Song(
            id = id,
            title = title,
            artist = owners?.firstOrNull()?.displayName ?: "Unknown Artist",
            album = "Unknown Album",
            albumArtUri = artUri,
            uri = Uri.parse("https://www.googleapis.com/drive/v3/files/$id?alt=media"),
            durationMs = 0L,
            format = formatLabel,
            bitrate = 0,
            sampleRateHz = 0,
            bitDepth = 16,
            fileSizeBytes = size?.toLong() ?: 0L,
            dateAdded = modifiedTime?.value ?: 0L,
            source = SongSource.GDRIVE,
            folder = accountEmail?.lowercase() ?: "GDRIVE",
            driveFileId = id,
            driveAccountEmail = accountEmail?.lowercase()
        )
    }
}
