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

        val isAlac = format.contains("alac") || song.title.contains("alac", ignoreCase = true)
        val isM4A = format == "m4a" || format == "mp4"
        val isWav = format.contains("wav")
        val isLossless = isAlac || isWav || format.contains("flac") || format.contains("dsd") || format.contains("aiff")

        // 1. Cloud routing
        if (isCloud) {
            // FFmpeg is much more robust for WAV and ALAC (especially over network/pipes)
            // Local MediaCodec often fails or has glitches with lossless over MediaDataSource
            if (isWav || isAlac) {
                Log.i(TAG, "Routing Cloud Lossless (${if (isWav) "WAV" else "ALAC"}) to FFmpeg: ${song.title}")
                return ffmpegAlacDecoder
            }

            // If it's an M4A, it MIGHT be ALAC. If we have high bitrate or unknown, probe it.
            if (isM4A && (song.bitrate > 500000 || song.bitrate == 0)) {
                val probedMime = probeAudioMime(song)
                if (probedMime?.contains("alac", ignoreCase = true) == true) {
                    Log.i(TAG, "Routing Cloud Probed ALAC to FFmpeg: ${song.title}")
                    return ffmpegAlacDecoder
                }
            }

            // Most other cloud files (AAC, FLAC, MP3) should use MediaCodec for better streaming
            Log.d(TAG, "Routing to MediaCodec (Cloud): ${song.title} [format=$format]")
            return mediaCodecDecoder
        }

        // 2. Local routing
        // For local files, FFmpeg is often more stable for ALAC and WAV (especially with seeking).
        if (isAlac || isM4A || isWav || format == "audio") {
            Log.i(TAG, "Routing to FFmpeg (Local Lossless): ${song.title} [format=$format]")
            return ffmpegAlacDecoder
        }

        // 3. Probing for local unknown formats
        if (song.bitrate == 0) {
             val probedMime = probeAudioMime(song)
             Log.i(TAG, "Probed mime for ${song.title}: $probedMime")
             if (probedMime?.contains("alac", ignoreCase = true) == true || 
                 probedMime?.contains("wav", ignoreCase = true) == true ||
                 probedMime?.contains("audio/x-wav", ignoreCase = true) == true) {
                 Log.i(TAG, "Routing to FFmpeg (Probed Lossless): ${song.title}")
                 return ffmpegAlacDecoder
             }
        }

        Log.d(TAG, "Routing to MediaCodec: ${song.title}")
        return mediaCodecDecoder
    }

    private suspend fun probeAudioMime(song: Song): String? = withContext(Dispatchers.IO) {
        // First try MediaExtractor as it's fast
        val extractor = MediaExtractor()
        try {
            val cachedFile = cloudCacheManager.getCachedFile(song)
            if (cachedFile != null) {
                extractor.setDataSource(cachedFile.absolutePath)
            } else if (song.source != com.beatflowy.app.model.SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(song) { false }
                if (dataSource != null) {
                    try {
                        extractor.setDataSource(dataSource)
                    } catch (e: Exception) {
                        // Fallback to direct URL if MediaDataSource fails for probing
                        val url = resolveDirectUrl(song)
                        val headers = resolveHeaders(song)
                        extractor.setDataSource(url, headers)
                    }
                } else {
                    val url = resolveDirectUrl(song)
                    val headers = resolveHeaders(song)
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
                    if (mime.contains("mp4a-latm", ignoreCase = true)) {
                        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                        if (bitrate > 500000) return@withContext "audio/alac"
                    }
                    return@withContext mime
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to probe decoder mime for ${song.title} with MediaExtractor", t)
        } finally {
            runCatching { extractor.release() }
        }

        // Fallback to FFprobe for definitive identification if MediaExtractor failed or was ambiguous
        try {
            Log.d(TAG, "Ambiguous mime for ${song.title}, falling back to FFprobe probe")
            val inputSource = if (song.source == com.beatflowy.app.model.SongSource.LOCAL) {
                com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, song.uri)
            } else {
                resolveDirectUrl(song)
            }
            
            val session = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(inputSource)
            val info = session.mediaInformation
            val audioStream = info?.streams?.firstOrNull { it.type == "audio" }
            if (audioStream != null) {
                return@withContext audioStream.codec
            }
        } catch (e: Exception) {
            Log.w(TAG, "FFprobe probe failed for ${song.title}", e)
        }
        
        null
    }

    private fun resolveDirectUrl(song: Song): String {
        return if (song.source == com.beatflowy.app.model.SongSource.GDRIVE) {
            "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
        } else {
            song.uri.toString()
        }
    }

    private suspend fun resolveHeaders(song: Song): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = "Beatflowy/2.8"
        if (song.source == com.beatflowy.app.model.SongSource.GDRIVE && song.driveAccountEmail != null) {
            val token = driveAccountRepository.getAccessToken(song.driveAccountEmail)
            if (token != null) {
                headers["Authorization"] = "Bearer $token"
            }
        }
        return headers
    }

    private companion object {
        const val TAG = "DecoderFactory"
    }

    private fun hasNativeAlacDecoder(): Boolean {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("audio/alac", true) }
            }
        } catch (e: Exception) {
            false
        }
    }
}
