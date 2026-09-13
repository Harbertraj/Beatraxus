package com.beatraxus.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.beatraxus.app.model.VideoChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class SceneChangeDetector(private val context: Context) {
    private val TAG = "SceneChangeDetector"
    private val SAMPLE_INTERVAL_MS = 1000L // 1 FPS
    private val MIN_CHAPTER_GAP_MS = 20000L // 20 seconds
    private val THUMB_SIZE = 16
    private val CHANGE_THRESHOLD = 15.0 // Percent change threshold

    suspend fun detectScenes(
        videoUri: Uri,
        videoId: String,
        durationMs: Long
    ): List<VideoChapterEntity> = withContext(Dispatchers.Default) {
        val chapters = mutableListOf<VideoChapterEntity>()
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(context, videoUri)
            
            var lastThumb: IntArray? = null
            var lastChapterTime = -MIN_CHAPTER_GAP_MS

            // Always add the first frame as a chapter
            val firstChapter = createChapter(retriever, videoId, 0L, chapters.size + 1)
            if (firstChapter != null) {
                chapters.add(firstChapter)
                lastChapterTime = 0L
            }

            for (timeMs in SAMPLE_INTERVAL_MS until durationMs step SAMPLE_INTERVAL_MS) {
                if (!isActive) break

                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue

                val currentThumb = getGrayscaleThumb(bitmap)
                bitmap.recycle()

                if (lastThumb != null) {
                    val diff = computeDifference(lastThumb, currentThumb)
                    if (diff > CHANGE_THRESHOLD && (timeMs - lastChapterTime) >= MIN_CHAPTER_GAP_MS) {
                        val chapter = createChapter(retriever, videoId, timeMs, chapters.size + 1)
                        if (chapter != null) {
                            chapters.add(chapter)
                            lastChapterTime = timeMs
                        }
                    }
                }
                lastThumb = currentThumb
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect scenes for $videoId", e)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        
        chapters
    }

    private fun getGrayscaleThumb(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, THUMB_SIZE, THUMB_SIZE, true)
        val pixels = IntArray(THUMB_SIZE * THUMB_SIZE)
        scaled.getPixels(pixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        
        val grayscale = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            grayscale[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
        scaled.recycle()
        return grayscale
    }

    private fun computeDifference(p1: IntArray, p2: IntArray): Double {
        var sumDiff = 0L
        for (i in p1.indices) {
            sumDiff += abs(p1[i] - p2[i])
        }
        // Average difference per pixel (0-255) as percentage
        return (sumDiff.toDouble() / p1.size.toDouble()) / 2.55
    }

    private fun createChapter(
        retriever: MediaMetadataRetriever,
        videoId: String,
        timeMs: Long,
        index: Int
    ): VideoChapterEntity? {
        val bitmap = retriever.getFrameAtTime(
            timeMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: return null

        val thumbFile = File(context.cacheDir, "chapters/${videoId}_$timeMs.jpg").apply {
            parentFile?.mkdirs()
        }
        
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 320, 180, true)
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            scaled.recycle()
            bitmap.recycle()
            
            return VideoChapterEntity(
                videoId = videoId,
                timestampMs = timeMs,
                label = "Chapter $index",
                thumbnailPath = thumbFile.absolutePath
            )
        } catch (e: Exception) {
            bitmap.recycle()
            return null
        }
    }
}
