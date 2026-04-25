package com.beatflowy.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.beatflowy.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DecoderFactory(
    private val context: Context,
    private val ffmpegAlacDecoder: FfmpegAlacDecoder,
    private val mediaCodecDecoder: MediaCodecAudioDecoder
) {
    suspend fun create(song: Song): AudioDecoder {
        if (song.format.equals("ALAC", ignoreCase = true)) {
            return ffmpegAlacDecoder
        }

        val probedMime = probeAudioMime(song)
        if (probedMime?.contains("alac", ignoreCase = true) == true) {
            Log.d(TAG, "Routing ${song.title} to FFmpeg via probed mime=$probedMime")
            return ffmpegAlacDecoder
        }

        return mediaCodecDecoder
    }

    private suspend fun probeAudioMime(song: Song): String? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return@withContext null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return@withContext mime
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to probe decoder mime for ${song.title}", t)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "DecoderFactory"
    }
}
