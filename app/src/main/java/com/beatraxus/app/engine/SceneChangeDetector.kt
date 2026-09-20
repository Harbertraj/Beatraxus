package com.beatraxus.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import com.beatraxus.app.model.VideoChapterEntity
import com.beatraxus.app.utils.VideoBackgroundWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

class SceneChangeDetector(private val context: Context) {
    private val TAG = "SceneChangeDetector"
    private val MIN_CHAPTER_GAP_MS = 20000L // 20 seconds
    private val CHANGE_THRESHOLD = 15.0 // Percent change threshold

    suspend fun detectScenes(
        videoUri: Uri,
        videoId: String,
        durationMs: Long
    ): List<VideoChapterEntity> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<VideoChapterEntity>()
        VideoBackgroundWork.mutex.withLock {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val retriever = MediaMetadataRetriever()
            
            try {
                retriever.setDataSource(context, videoUri)
                
                var lastThumb: IntArray? = null
                var lastChapterTime = -MIN_CHAPTER_GAP_MS
                val sampleIntervalMs = max(1000L, durationMs / 150)
    
                // Always add the first frame as a chapter
                val firstChapter = createChapter(retriever, videoId, 0L, chapters.size + 1)
                if (firstChapter != null) {
                    chapters.add(firstChapter)
                    lastChapterTime = 0L
                }
    
                for (timeMs in sampleIntervalMs until durationMs step sampleIntervalMs) {
                    if (!isActive) break
    
                    val timeUs = timeMs * 1000L
                    var bitmap: Bitmap? = null
                    if (Build.VERSION.SDK_INT >= 27) {
                        try {
                            bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 36)
                        } catch (e: Exception) { }
                    }
                    if (bitmap == null) {
                        val orig = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (orig != null) {
                            bitmap = Bitmap.createScaledBitmap(orig, 64, 36, true)
                            orig.recycle()
                        }
                    }
                    
                    if (bitmap == null) continue
    
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
        }
        
        chapters
    }

    private fun getGrayscaleThumb(scaled: Bitmap): IntArray {
        val width = 64
        val height = 36
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val grayscale = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            grayscale[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
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
        val timeUs = timeMs * 1000L
        var scaled: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                scaled = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } catch (e: Exception) {}
        }
        if (scaled == null) {
            val orig = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (orig != null) {
                scaled = Bitmap.createScaledBitmap(orig, 320, 180, true)
                orig.recycle()
            }
        }
        if (scaled == null) return null

        val thumbFile = File(context.cacheDir, "chapters/${videoId}_$timeMs.jpg").apply {
            parentFile?.mkdirs()
        }
        
        try {
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            scaled.recycle()
            
            return VideoChapterEntity(
                videoId = videoId,
                timestampMs = timeMs,
                label = "Chapter $index",
                thumbnailPath = thumbFile.absolutePath
            )
        } catch (e: Exception) {
            scaled.recycle()
            return null
        }
    }
}
