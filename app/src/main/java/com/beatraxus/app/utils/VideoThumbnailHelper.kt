package com.beatraxus.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object VideoThumbnailHelper {
    private const val TAG = "VideoThumbnailHelper"
    private const val THUMBNAIL_DIR = "video_thumbnails"

    suspend fun getThumbnail(context: Context, videoUri: Uri, videoId: String): Uri? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, THUMBNAIL_DIR).apply { mkdirs() }
        val thumbnailFile = File(cacheDir, "$videoId.jpg")

        if (thumbnailFile.exists() && thumbnailFile.length() > 0) {
            return@withContext Uri.fromFile(thumbnailFile)
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                FileOutputStream(thumbnailFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                }
                return@withContext Uri.fromFile(thumbnailFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail for $videoUri", e)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        null
    }
}
