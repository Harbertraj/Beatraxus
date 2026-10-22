package com.beatraxus.app.util

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.beatraxus.app.model.VideoSnapshotSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Takes a picture of the video that is on screen and saves it to the gallery (Pictures/Beatraxus).
 *
 * Which surface the picture comes from depends on how the player draws the video:
 *  - SurfaceView (normal playback) and the FPS Booster GL views: copied straight from the video surface with
 *    PixelCopy, at the video's own resolution. No controls or subtitles in the picture.
 *  - TextureView (used while rotate / flip / colour grading is on): the picture is copied from the window at the
 *    TextureView's position, so rotation, mirror and colour grading are in it exactly as shown. The caller hides
 *    the on-screen controls first ([needsClearScreen]); subtitles are in the picture on this path.
 */
@UnstableApi
object VideoSnapshot {

    sealed class Result {
        data class Saved(val uri: Uri, val name: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private fun surfaceOf(playerView: PlayerView?): View? =
        (playerView?.tag as? SurfaceView) ?: playerView?.videoSurfaceView

    /** True when the picture is taken from the window, so the controls on top of the video must be hidden first. */
    fun needsClearScreen(playerView: PlayerView?): Boolean = surfaceOf(playerView) is TextureView

    suspend fun captureAndSave(
        context: Context,
        playerView: PlayerView?,
        title: String?,
        positionMs: Long,
        videoHeightPx: Int
    ): Result {
        val surface = surfaceOf(playerView) ?: return Result.Failed("No video picture to capture")
        if (surface.width <= 0 || surface.height <= 0) return Result.Failed("No video picture to capture")

        val bitmap = try {
            when (surface) {
                is SurfaceView -> copyFromSurfaceView(surface, videoHeightPx)
                is TextureView -> copyFromWindow(surface)
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("Could not capture the picture. Wait for the video to show a frame and try again")

        val name = VideoSnapshotSpec.fileName(title, positionMs, System.currentTimeMillis())
        return withContext(Dispatchers.IO) {
            try {
                save(context, bitmap, name)
            } catch (e: Exception) {
                Result.Failed("Could not save the picture: ${e.message ?: "storage error"}")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun copyFromSurfaceView(view: SurfaceView, videoHeightPx: Int): Bitmap? {
        if (!view.holder.surface.isValid) return null
        val (w, h) = VideoSnapshotSpec.targetSize(view.width, view.height, videoHeightPx)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            PixelCopy.request(view, bitmap, { code -> if (cont.isActive) cont.resume(code == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()))
        }
        if (!ok) bitmap.recycle()
        return if (ok) bitmap else null
    }

    private suspend fun copyFromWindow(view: TextureView): Bitmap? {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window ?: return null
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        val decor = window.decorView
        if (!rect.intersect(0, 0, decor.width, decor.height) || rect.isEmpty) return null
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            PixelCopy.request(window, rect, bitmap, { code -> if (cont.isActive) cont.resume(code == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()))
        }
        if (!ok) bitmap.recycle()
        return if (ok) bitmap else null
    }

    private fun save(context: Context, bitmap: Bitmap, name: String): Result {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${VideoSnapshotSpec.ALBUM}")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.Failed("The gallery refused the picture")
            return try {
                val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
                if (!written) throw java.io.IOException("write failed")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                Result.Saved(uri, name)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        // Android 8 / 9: write the file ourselves (the storage permission the library scan already asks for).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return Result.Failed("Allow storage access in Android settings to save pictures")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), VideoSnapshotSpec.ALBUM)
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Failed("Could not create the Pictures/${VideoSnapshotSpec.ALBUM} folder")
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        return Result.Saved(Uri.fromFile(file), name)
    }
}
