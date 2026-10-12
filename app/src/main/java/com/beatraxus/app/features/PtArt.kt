package com.beatraxus.app.features

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.beatraxus.app.model.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Album art for Play Together.
 *
 * A song received from the controller is just an audio file, and the player only shows
 * `Song.albumArtUri`, so without this the received song had no cover. The controller sends a small
 * JPEG of the cover together with the song ([coverFile]); if it did not (older app version, or the
 * song has no cover) the receiver looks for a picture embedded in the audio file ([extractEmbedded]).
 *
 * Both functions read files and decode images, so call them off the main thread.
 */
internal object PtArt {
    private const val MAX_SIDE = 600
    private const val MAX_BYTES = 160 * 1024

    /**
     * Controller: a JPEG of [song]'s cover (at most [MAX_SIDE] px, about 160 KB) in the cache, or
     * null when the song has no cover. The file is reused for every phone and every request.
     */
    fun coverFile(context: Context, song: Song): File? {
        val dir = File(context.cacheDir, "pt_art_send").apply { mkdirs() }
        val name = hash(song.id)
        val file = File(dir, "$name.jpg")
        if (file.exists() && file.length() > 0) return file
        val none = File(dir, "$name.none")
        if (none.exists()) return null
        val bytes = coverBytes(context, song)
        if (bytes == null) {
            try { none.createNewFile() } catch (_: Exception) { }
            return null
        }
        return try { file.writeBytes(bytes); file } catch (_: Exception) { null }
    }

    /** Receiver: save the picture embedded in [audio] to [out]. Returns true if there was one. */
    fun extractEmbedded(context: Context, audio: File, out: File): Boolean {
        val raw = embeddedPicture(context, Uri.fromFile(audio)) ?: return false
        val jpeg = toJpeg(raw) ?: return false
        return try {
            out.parentFile?.mkdirs()
            out.writeBytes(jpeg)
            true
        } catch (_: Exception) {
            out.delete()
            false
        }
    }

    private fun coverBytes(context: Context, song: Song): ByteArray? {
        val raw = readUri(context, song.albumArtUri) ?: embeddedPicture(context, song.uri) ?: return null
        return toJpeg(raw)
    }

    private fun readUri(context: Context, uri: Uri?): ByteArray? {
        if (uri == null) return null
        val scheme = uri.scheme
        if (scheme != "content" && scheme != "file") return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun embeddedPicture(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /** Decodes [raw], shrinks it to at most [MAX_SIDE] px and re-encodes it as a small JPEG. */
    private fun toJpeg(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else decoded
        try {
            var result: ByteArray? = null
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                result = out.toByteArray()
                if (result.size <= MAX_BYTES) break
            }
            return result?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES * 2 }
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
