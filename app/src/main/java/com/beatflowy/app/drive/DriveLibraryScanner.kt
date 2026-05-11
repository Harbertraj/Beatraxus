package com.beatflowy.app.drive

import android.content.Context
import android.net.Uri
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveLibraryScanner(private val context: Context) {

    suspend fun scanAccount(credential: GoogleAccountCredential): List<Song> = withContext(Dispatchers.IO) {
        try {
            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            val driveService = Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("BeatFlowy")
                .build()

            val result = driveService.files().list()
                .setQ("mimeType contains 'audio/' and trashed = false")
                .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, owners)")
                .setPageSize(1000)
                .execute()

            result.files?.map { file ->
                Song(
                    id = file.id, // Using file.id directly as String matches our Song model better
                    title = file.name.substringBeforeLast('.'),
                    artist = file.owners?.firstOrNull()?.displayName ?: "Google Drive",
                    album = "Google Drive",
                    albumArtUri = null,
                    uri = Uri.parse("https://www.googleapis.com/drive/v3/files/${file.id}?alt=media"),
                    durationMs = 0L,
                    format = file.name.substringAfterLast('.').uppercase(),
                    bitrate = 0,
                    sampleRateHz = 44100,
                    bitDepth = 16,
                    source = SongSource.GDRIVE,
                    driveFileId = file.id,
                    driveAccountEmail = credential.selectedAccountName
                )
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
