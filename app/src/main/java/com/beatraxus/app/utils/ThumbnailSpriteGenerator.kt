package com.beatraxus.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object ThumbnailSpriteGenerator {
    private const val TAG = "ThumbnailSpriteGen"
    private const val SPRITE_DIR = "video_scrub_previews"
    private const val THUMB_WIDTH = 160
    private const val THUMB_HEIGHT = 90
    private const val COLUMNS = 10
    private const val MAX_CACHED_VIDEOS = 50

    data class SpriteMetadata(
        val intervalMs: Long,
        val frameCount: Int,
        val thumbWidth: Int,
        val thumbHeight: Int,
        val columns: Int
    )

    suspend fun generateSpriteSheet(
        context: Context,
        videoUri: Uri,
        videoId: String
    ): SpriteMetadata? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, SPRITE_DIR).apply { mkdirs() }
        val spriteFile = File(cacheDir, "$videoId.webp")
        val metaFile = File(cacheDir, "$videoId.json")

        if (spriteFile.exists() && metaFile.exists()) {
            return@withContext loadMetadata(metaFile)
        }

        cleanupCache(cacheDir)

        VideoBackgroundWork.mutex.withLock {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                if (durationMs <= 0) return@withContext null
    
                val intervalMs = max(10_000L, durationMs / 60)
                val frameCount = (durationMs / intervalMs).toInt().coerceAtLeast(1)
                val rows = (frameCount + COLUMNS - 1) / COLUMNS
            
            val spriteSheet = Bitmap.createBitmap(
                COLUMNS * THUMB_WIDTH,
                rows * THUMB_HEIGHT,
                Bitmap.Config.RGB_565
            )
            val canvas = Canvas(spriteSheet)

            for (i in 0 until frameCount) {
                if (!isActive) {
                    spriteSheet.recycle()
                    return@withContext null
                }

                val timeUs = i * intervalMs * 1000L
                var scaledFrame: Bitmap? = null
                if (Build.VERSION.SDK_INT >= 27) {
                    try {
                        scaledFrame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH, THUMB_HEIGHT)
                    } catch (e: Exception) { }
                }
                if (scaledFrame == null) {
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        scaledFrame = Bitmap.createScaledBitmap(frame, THUMB_WIDTH, THUMB_HEIGHT, true)
                        frame.recycle()
                    }
                }
                
                if (scaledFrame != null) {
                    val x = (i % COLUMNS) * THUMB_WIDTH
                    val y = (i / COLUMNS) * THUMB_HEIGHT
                    canvas.drawBitmap(scaledFrame, x.toFloat(), y.toFloat(), null)
                    scaledFrame.recycle()
                }
            }

            FileOutputStream(spriteFile).use { out ->
                spriteSheet.compress(Bitmap.CompressFormat.WEBP, 75, out)
            }
            spriteSheet.recycle()

            val metadata = SpriteMetadata(intervalMs, frameCount, THUMB_WIDTH, THUMB_HEIGHT, COLUMNS)
            saveMetadata(metaFile, metadata)
            
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate sprite sheet for $videoId", e)
            null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        }
    }

    fun getFrameFromSprite(
        spriteSheet: Bitmap,
        metadata: SpriteMetadata,
        positionMs: Long
    ): Bitmap? {
        val frameIndex = (positionMs / metadata.intervalMs).toInt().coerceIn(0, metadata.frameCount - 1)
        val x = (frameIndex % metadata.columns) * metadata.thumbWidth
        val y = (frameIndex / metadata.columns) * metadata.thumbHeight
        
        return try {
            Bitmap.createBitmap(
                spriteSheet,
                x,
                y,
                metadata.thumbWidth,
                metadata.thumbHeight
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getSpriteFile(context: Context, videoId: String): File {
        return File(File(context.cacheDir, SPRITE_DIR), "$videoId.webp")
    }

    fun getMetadataFile(context: Context, videoId: String): File {
        return File(File(context.cacheDir, SPRITE_DIR), "$videoId.json")
    }

    private fun loadMetadata(file: File): SpriteMetadata? {
        return try {
            val json = JSONObject(file.readText())
            SpriteMetadata(
                json.getLong("intervalMs"),
                json.getInt("frameCount"),
                json.getInt("thumbWidth"),
                json.getInt("thumbHeight"),
                json.getInt("columns")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveMetadata(file: File, metadata: SpriteMetadata) {
        try {
            val json = JSONObject().apply {
                put("intervalMs", metadata.intervalMs)
                put("frameCount", metadata.frameCount)
                put("thumbWidth", metadata.thumbWidth)
                put("thumbHeight", metadata.thumbHeight)
                put("columns", metadata.columns)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }

    private fun cleanupCache(cacheDir: File) {
        val files = cacheDir.listFiles() ?: return
        if (files.size > MAX_CACHED_VIDEOS * 2) { // 2 files per video (webp + json)
            val sortedFiles = files.sortedBy { it.lastModified() }
            val toDelete = files.size - (MAX_CACHED_VIDEOS * 2)
            for (i in 0 until toDelete) {
                sortedFiles[i].delete()
            }
        }
    }
}
