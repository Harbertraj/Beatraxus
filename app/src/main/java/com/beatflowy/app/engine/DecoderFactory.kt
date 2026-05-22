package com.beatflowy.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.repository.DriveAccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DecoderFactory(
    private val context: Context,
    private val driveAccountRepository: DriveAccountRepository,
    private val cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager,
    private val ffmpegAlacDecoder: FfmpegAlacDecoder,
    private val mediaCodecDecoder: MediaCodecAudioDecoder
) {
    suspend fun create(song: Song): AudioDecoder {
        val format = song.format.lowercase()
        val isCloud = song.source == com.beatflowy.app.model.SongSource.GDRIVE ||
                      song.source == com.beatflowy.app.model.SongSource.WEB ||
                      song.source == com.beatflowy.app.model.SongSource.TELEGRAM

        // 1. Explicit ALAC or problematic cloud formats
        // Cloud WAV and M4A/ALAC are routed to FFmpeg for better stability and range-request handling.
        if (format.contains("alac") || (isCloud && (format == "m4a" || format == "mp4" || format == "wav"))) {
            Log.d(TAG, "Routing ${format.uppercase()} to FFmpeg: ${song.title}")
            return ffmpegAlacDecoder
        }

        // 2. Heuristic for ALAC in M4A (Local)
        val isM4A = format == "m4a" || format == "mp4"
        if (isM4A && (song.bitrate > 450000 || song.bitDepth > 16)) {
            Log.d(TAG, "Routing local M4A to FFmpeg via heuristic (bitrate=${song.bitrate}): ${song.title}")
            return ffmpegAlacDecoder
        }

        // 3. Probing for cloud songs that aren't M4A/WAV (e.g. unknown extensions)
        if (isCloud && song.bitrate == 0) {
             val probedMime = probeAudioMime(song)
             if (probedMime?.contains("alac", ignoreCase = true) == true) {
                 return ffmpegAlacDecoder
             }
        }

        return mediaCodecDecoder
    }

    private suspend fun probeAudioMime(song: Song): String? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            val cachedFile = cloudCacheManager.getCachedFile(song)
            if (cachedFile != null) {
                extractor.setDataSource(cachedFile.absolutePath)
            } else if (song.source != com.beatflowy.app.model.SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(song) { false }
                if (dataSource != null) {
                    extractor.setDataSource(dataSource)
                } else {
                    val url = if (song.source == com.beatflowy.app.model.SongSource.GDRIVE) {
                        "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
                    } else {
                        song.uri.toString()
                    }
                    val headers = mutableMapOf<String, String>()
                    if (song.source == com.beatflowy.app.model.SongSource.GDRIVE && song.driveAccountEmail != null) {
                        val token = driveAccountRepository.getAccessToken(song.driveAccountEmail)
                        if (token != null) {
                            headers["Authorization"] = "Bearer $token"
                        }
                    }
                    extractor.setDataSource(url, headers)
                }
            } else {
                try {
                    extractor.setDataSource(context, song.uri, null)
                } catch (e: Exception) {
                    context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    } ?: return@withContext null
                }
            }

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
