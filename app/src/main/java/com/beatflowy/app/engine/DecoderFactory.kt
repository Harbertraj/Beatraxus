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
        if (song.format.equals("ALAC", ignoreCase = true)) {
            return ffmpegAlacDecoder
        }

        // Check if it's a known ALAC in an M4A container via heuristic or explicit probe
        val isM4A = song.format.equals("M4A", ignoreCase = true) || song.format.equals("MP4", ignoreCase = true)
        if (isM4A && (song.bitrate > 450000 || song.bitDepth > 16)) {
            Log.d(TAG, "Routing ${song.title} to FFmpeg via heuristic (M4A with bitrate=${song.bitrate})")
            return ffmpegAlacDecoder
        }

        // Optimization: For cloud songs, trust the format/extension for routing to avoid
        // high-latency MediaExtractor probing. Cloud songs will call setDataSource
        // again in the actual decoder, so we avoid doing it twice here.
        if (song.source == com.beatflowy.app.model.SongSource.GDRIVE ||
            song.source == com.beatflowy.app.model.SongSource.WEB ||
            song.source == com.beatflowy.app.model.SongSource.TELEGRAM) {
            
            val isWav = song.format.equals("WAV", ignoreCase = true)
            // If it's M4A or WAV and we haven't enriched it yet (bitrate=0), we MUST probe 
            // because it might be ALAC which MediaCodec can't handle on many devices.
            if ((isM4A || isWav) && song.bitrate == 0) {
                Log.d(TAG, "Cloud ${song.format} without metadata, probing to avoid MediaCodec failure: ${song.title}")
            } else {
                return mediaCodecDecoder
            }
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
            val cachedFile = cloudCacheManager.getCachedFile(song)
            if (cachedFile != null) {
                extractor.setDataSource(cachedFile.absolutePath)
            } else if (song.source != com.beatflowy.app.model.SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(song)
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
