package com.beatraxus.app.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.beatraxus.app.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoLibraryScanner(private val context: Context) {

    suspend fun scanVideos(): List<Video> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<Video>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATA
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(displayNameColumn) ?: "Unknown"
                val title = cursor.getString(titleColumn) ?: displayName
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val mimeType = cursor.getString(mimeTypeColumn) ?: "video/*"
                val dateAdded = cursor.getLong(dateAddedColumn)
                val data = cursor.getString(dataColumn) ?: ""
                
                val contentUri = ContentUris.withAppendedId(collection, id)
                val folderPath = data.substringBeforeLast("/", "Unknown")

                // HDR Detection (API 24+)
                val isHdr = detectHdr(contentUri)

                videos.add(
                    Video(
                        id = id.toString(),
                        uri = contentUri,
                        title = title,
                        displayName = displayName,
                        folderPath = folderPath,
                        durationMs = duration,
                        sizeBytes = size,
                        resolutionWidth = width,
                        resolutionHeight = height,
                        mimeType = mimeType,
                        dateAdded = dateAdded,
                        thumbnailUri = null, // Will be handled in Phase 2
                        isHdr = isHdr
                    )
                )
            }
        }
        videos
    }

    private fun detectHdr(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // METADATA_KEY_COLOR_TRANSFER = 36 (Available from API 30, but value 36 works on 24+ via raw int)
            val colorTransfer = retriever.extractMetadata(36)
            // 6 = HLG, 7 = PQ (HDR10/HDR10+)
            colorTransfer == "6" || colorTransfer == "7"
        } catch (e: Exception) {
            false
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }
}
