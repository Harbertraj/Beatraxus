package com.beatflowy.app.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.beatflowy.app.model.AlbumItem
import com.beatflowy.app.model.DownloadItem
import com.beatflowy.app.model.DownloadQuality
import com.beatflowy.app.model.DownloadSettings
import com.beatflowy.app.model.DownloadStatus
import com.beatflowy.app.model.FilenameTemplate
import com.beatflowy.app.model.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.InputStream
import java.io.OutputStream

class QobuzRepository(private val context: Context) {

    private val httpClient = QobuzApiService.getOkHttpClient()

    private val apiService: QobuzApiService by lazy {
        Retrofit.Builder()
            .baseUrl(QobuzApiService.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpClient)
            .build()
            .create(QobuzApiService::class.java)
    }

    private suspend fun <T> handleResponse(response: Response<QobuzResponse<T>>): T {
        if (response.isSuccessful) {
            val body = response.body()
            if (body?.success == true) {
                return body.data ?: throw Exception("Response success but data is null")
            } else {
                val errorMsg = body?.error ?: "Unknown server error"
                if (errorMsg.contains("Captcha required", ignoreCase = true)) {
                    throw Exception("Captcha required")
                }
                throw Exception(errorMsg)
            }
        } else {
            val errorBody = response.errorBody()?.string()
            if (errorBody?.contains("Captcha required", ignoreCase = true) == true) {
                throw Exception("Captcha required")
            }
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        try {
            val searchData = handleResponse(apiService.searchMusic(query = query, offset = 0))
            
            android.util.Log.d("BeatFlowy", "Raw response - tracks: ${searchData.tracks}, albums: ${searchData.albums}")
            
            val tracks = searchData.tracks?.items?.takeIf { it.isNotEmpty() }?.map { track ->
                DownloadItem(
                    id = track.id.toString(),
                    title = track.title,
                    artist = track.performer?.name ?: "Unknown Artist",
                    album = track.album.title,
                    quality = mapToDownloadQuality(track.maxBitDepth ?: 0, track.maxSampleRate ?: 0f),
                    coverUrl = track.album.image?.large ?: track.album.image?.thumbnail ?: track.album.image?.small,
                    status = DownloadStatus.QUEUED,
                    progressPercent = 0,
                    fileSizeBytes = 0
                )
            } ?: searchData.albums?.items?.map { album ->
                DownloadItem(
                    id = album.id,
                    title = album.title,
                    artist = album.artist?.name ?: "Unknown Artist",
                    album = album.title,
                    quality = DownloadQuality.Lossless,
                    coverUrl = album.image?.large ?: album.image?.small,
                    status = DownloadStatus.QUEUED,
                    progressPercent = 0,
                    fileSizeBytes = 0
                )
            }?.takeIf { it.isNotEmpty() }
            ?: throw Exception("No results found for \"${query.trim()}\"")

            val albums = searchData.albums?.items?.map { album ->
                AlbumItem(
                    id = album.id,
                    title = album.title,
                    artist = album.artist?.name ?: "Unknown Artist",
                    coverUrl = album.image?.large ?: album.image?.thumbnail ?: album.image?.small,
                    tracksCount = album.tracksCount ?: 0
                )
            } ?: emptyList()

            SearchResults(tracks = tracks, albums = albums)
        } catch (e: Exception) {
            android.util.Log.e("BeatFlowy", "Search failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    suspend fun getAlbumTracks(albumId: String): List<DownloadItem> = withContext(Dispatchers.IO) {
        try {
            val searchData = handleResponse(apiService.getAlbumTracks(albumId))
            val tracks = searchData.tracks?.items ?: return@withContext emptyList()
            tracks.map { track ->
                DownloadItem(
                    id = track.id.toString(),
                    title = track.title,
                    artist = track.performer?.name ?: "Unknown Artist",
                    album = track.album.title,
                    quality = mapToDownloadQuality(track.maxBitDepth ?: 0, track.maxSampleRate ?: 0f),
                    coverUrl = track.album.image?.large ?: track.album.image?.thumbnail ?: track.album.image?.small,
                    status = DownloadStatus.QUEUED,
                    progressPercent = 0,
                    fileSizeBytes = 0
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BeatFlowy", "Get album tracks failed: ${e.message}")
            if (e.message?.contains("Captcha required") == true) throw e
            emptyList()
        }
    }

    private fun mapToDownloadQuality(bitDepth: Int, sampleRate: Float): DownloadQuality {
        return when {
            bitDepth > 16 -> DownloadQuality.HiRes24Bit
            else -> DownloadQuality.Lossless
        }
    }

    private suspend fun getDownloadUrl(trackId: String, qualityCode: Int): String =
        withContext(Dispatchers.IO) {
            val downloadData = handleResponse(apiService.getDownloadUrl(trackId = trackId, quality = qualityCode))
            downloadData.url ?: throw Exception("Server returned no download URL")
        }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    suspend fun downloadTrack(
        item: DownloadItem,
        settings: DownloadSettings,
        destinationTreeUri: Uri,
        onFileDownloaded: (Uri) -> Unit
    ): Flow<DownloadProgress> = flow {
        try {
            emit(DownloadProgress.Started)

            val downloadUrl = getDownloadUrl(item.id, item.quality.qualityCode)

            val response = apiService.downloadFile(downloadUrl)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                if (errorBody?.contains("Captcha required", ignoreCase = true) == true) {
                    throw Exception("Captcha required")
                }
                throw Exception("Failed to download file: HTTP ${response.code()}")
            }

            // Check Content-Type to see if it's a captcha page (HTML) instead of audio
            val contentType = response.headers()["Content-Type"]
            if (contentType?.contains("text/html", ignoreCase = true) == true) {
                throw Exception("Captcha required")
            }

            val body = response.body() ?: throw Exception("Empty response body from download URL")

            val artistSanitized = sanitizeFileName(item.artist)
            val albumSanitized = sanitizeFileName(item.album)
            val titleSanitized = sanitizeFileName(item.title)

            val fileName = when (settings.filenameTemplate) {
                FilenameTemplate.ARTIST_TITLE -> "$artistSanitized - $titleSanitized.flac"
                FilenameTemplate.TITLE_ONLY -> "$titleSanitized.flac"
                FilenameTemplate.ARTIST_ALBUM_TITLE -> "$artistSanitized - $albumSanitized - $titleSanitized.flac"
            }

            // Enhanced Tree URI recovery and validation
            val tree = try {
                // Ensure we have persistable access
                context.contentResolver.takePersistableUriPermission(
                    destinationTreeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                DocumentFile.fromTreeUri(context, destinationTreeUri)
            } catch (e: Exception) {
                android.util.Log.e("BeatFlowy", "Failed to access tree URI: ${e.message}")
                null
            } ?: throw Exception("Download folder access denied. Please re-select it in Settings.")

            if (!tree.canWrite()) {
                throw Exception("No write permission for the selected folder. Please re-select it in Settings.")
            }

            val destinationFolder = if (settings.createAlbumSubfolders) {
                val artistDir = tree.findFile(artistSanitized) ?: tree.createDirectory(artistSanitized)
                artistDir?.let { it.findFile(albumSanitized) ?: it.createDirectory(albumSanitized) } ?: tree
            } else {
                tree
            }

            val existingFile = destinationFolder.findFile(fileName)
            if (existingFile != null && !settings.overwriteExisting) {
                onFileDownloaded(existingFile.uri)
                emit(DownloadProgress.Finished(existingFile.uri))
                return@flow
            }

            val file = existingFile ?: destinationFolder.createFile("audio/flac", fileName)
                ?: throw Exception("Failed to create output file. Check storage permissions.")

            val totalBytes = body.contentLength()
            var bytesRead = 0L
            val buffer = ByteArray(16384)
            var read: Int

            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                body.byteStream().use { input ->
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            emit(DownloadProgress.InProgress((bytesRead * 100 / totalBytes).toInt()))
                        }
                    }
                }
            } ?: throw Exception("Failed to open output stream")

            onFileDownloaded(file.uri)
            emit(DownloadProgress.Finished(file.uri))
        } catch (e: Exception) {
            android.util.Log.e("BeatFlowy", "Download failure: ${e.message}", e)
            emit(DownloadProgress.Failed(e.message ?: "Unknown error"))
        }
    }
}

sealed class DownloadProgress {
    object Started : DownloadProgress()
    data class InProgress(val progress: Int) : DownloadProgress()
    data class Finished(val fileUri: Uri) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}
