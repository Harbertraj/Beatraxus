package com.beatflowy.app.drive

import android.content.Context
import android.net.Uri
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class DriveLibraryScanner(private val context: Context) {

    companion object {
        /**
         * WHY TWO QUERY PARTS?
         *
         * Google Drive assigns MIME types based on its own detection — NOT the file extension.
         * FLAC, WAV, ALAC, APE, AIFF and many hi-res formats are stored as
         * "application/octet-stream" (generic binary), so querying only
         * `mimeType contains 'audio/'` will MISS them entirely.
         *
         * Fix: combine the MIME type check (catches MP3, AAC, OGG etc.) with
         * explicit extension checks (catches FLAC, WAV, ALAC and every other
         * format Drive mis-classifies). name contains is case-insensitive in
         * the Drive API so '.flac' also matches '.FLAC'.
         */
        private val AUDIO_EXTENSIONS = listOf(
            // Lossless
            ".flac", ".wav", ".alac", ".aiff", ".aif", ".ape", ".wv", ".tta",
            ".dsf", ".dff", ".dsd",
            // Lossy
            ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".mp4",
            // Container / misc
            ".mka", ".webm", ".caf", ".ra"
        )

        /**
         * Drive API query:
         *   Part 1 — mimeType contains 'audio/'  → catches files Drive correctly marks as audio
         *   Part 2 — name contains '.ext'         → catches files Drive marks as octet-stream
         *   Combined with OR, then AND trashed=false
         *
         * Note: Drive API query strings have no hard character limit in practice,
         * but we split into two separate API calls if needed to stay safe.
         * Here the combined string is well within limits (~800 chars).
         */
        fun buildDriveQuery(): String {
            val extensionClauses = AUDIO_EXTENSIONS.joinToString(" or ") { ext ->
                "name contains '$ext'"
            }
            return "(mimeType contains 'audio/' or $extensionClauses) and trashed = false"
        }

        /** Supported extensions for local post-filter (ensures no false positives). */
        private val SUPPORTED_EXTENSIONS = AUDIO_EXTENSIONS.toHashSet()

        fun isSupportedAudioFile(filename: String, mimeType: String? = null): Boolean {
            val ext = ".${filename.substringAfterLast('.', "").lowercase()}"
            return ext in SUPPORTED_EXTENSIONS || (mimeType?.startsWith("audio/") == true)
        }
    }

    // -------------------------------------------------------------------------
    // Flow-based scan — emits progressively as pages arrive
    // -------------------------------------------------------------------------

    fun scanAccountFlow(credential: GoogleAccountCredential): Flow<List<Song>> = flow {
        try {
            val driveService = buildDriveService(credential)
            val query = buildDriveQuery()
            var pageToken: String? = null

            do {
                val result = driveService.files().list()
                    .setQ(query)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, owners)")
                    .setPageSize(1000)
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                    .execute()

                val pageSongs = result.files
                    ?.filter { isSupportedAudioFile(it.name, it.mimeType) }   // post-filter: drop any false positives
                    ?.map { file -> file.toSong(credential.selectedAccountName) }
                    ?: emptyList()

                if (pageSongs.isNotEmpty()) {
                    emit(pageSongs) // Emit only the new page to avoid redundant DB writes
                }

                pageToken = result.nextPageToken
            } while (pageToken != null && currentCoroutineContext().isActive)

        } catch (e: UserRecoverableAuthIOException) {
            DrivePlaybackHelper.authRecoveryFlow.tryEmit(e.intent)
            throw e
        } catch (e: Exception) {
            DrivePlaybackHelper.errorState.tryEmit("Drive scan error: ${e.message}")
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Suspend-based scan — returns complete list (used for cache warming)
    // -------------------------------------------------------------------------

    suspend fun scanAccount(credential: GoogleAccountCredential): List<Song> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            try {
                val driveService = buildDriveService(credential)
                val query = buildDriveQuery()
                var pageToken: String? = null

                while (true) {
                    val result = driveService.files().list()
                        .setQ(query)
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, owners)")
                        .setPageSize(1000)
                        .apply { if (pageToken != null) setPageToken(pageToken) }
                        .execute()

                    val pageSongs = result.files
                        ?.filter { isSupportedAudioFile(it.name, it.mimeType) }
                        ?.map { file -> file.toSong(credential.selectedAccountName) }
                        ?: emptyList()

                    songs.addAll(pageSongs)
                    pageToken = result.nextPageToken
                    if (pageToken == null) break
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Return whatever was collected before the error
            }
            songs
        }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun buildDriveService(credential: GoogleAccountCredential): Drive =
        Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Beatraxus")
            .build()

    /**
     * Test query as requested.
     * Can be used for debugging or verification.
     */
    suspend fun testDriveQuery(credential: GoogleAccountCredential): com.google.api.services.drive.model.FileList =
        withContext(Dispatchers.IO) {
            val driveService = buildDriveService(credential)
            driveService.files().list()
                .setQ("mimeType contains 'audio/'")
                .setFields("files(id,name,size,mimeType)")
                .execute()
        }

    private fun com.google.api.services.drive.model.File.toSong(accountEmail: String?): Song {
        val ext = name.substringAfterLast('.', "").uppercase()
        val title = name.substringBeforeLast('.')
        return Song(
            id = id,
            title = title,
            artist = owners?.firstOrNull()?.displayName ?: "Unknown Artist",
            album = "Unknown Album",
            albumArtUri = null,
            uri = Uri.parse("https://www.googleapis.com/drive/v3/files/$id?alt=media"),
            durationMs = 0L,
            format = ext.ifEmpty { "AUDIO" },
            bitrate = 0,
            sampleRateHz = 0,
            bitDepth = 16,
            fileSizeBytes = size.toLong(),
            dateAdded = modifiedTime?.value ?: 0L,
            source = SongSource.GDRIVE,
            driveFileId = id,
            driveAccountEmail = accountEmail?.lowercase()
        )
    }
}