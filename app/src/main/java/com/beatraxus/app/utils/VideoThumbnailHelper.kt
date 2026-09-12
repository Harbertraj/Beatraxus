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

    data class VideoEnrichmentResult(
        val thumbnailUri: Uri?,
        val isHdr: Boolean
    )

    suspend fun enrichVideo(context: Context, videoUri: Uri, videoId: String): VideoEnrichmentResult = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, THUMBNAIL_DIR).apply { mkdirs() }
        val thumbnailFile = File(cacheDir, "$videoId.jpg")

        var thumbnailUri: Uri? = if (thumbnailFile.exists() && thumbnailFile.length() > 0) {
            Uri.fromFile(thumbnailFile)
        } else null
        
        var isHdr = false

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            
            // 1. Thumbnail generation if missing
            if (thumbnailUri == null) {
                val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    FileOutputStream(thumbnailFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    }
                    thumbnailUri = Uri.fromFile(thumbnailFile)
                }
            }
            
            // 2. HDR Detection (API 24+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // METADATA_KEY_COLOR_TRANSFER = 36
                val colorTransfer = retriever.extractMetadata(36)
                isHdr = colorTransfer == "6" || colorTransfer == "7"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enrich video $videoUri", e)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        
        VideoEnrichmentResult(thumbnailUri, isHdr)
    }

    suspend fun getThumbnail(context: Context, videoUri: Uri, videoId: String): Uri? {
        return enrichVideo(context, videoUri, videoId).thumbnailUri
    }
}
