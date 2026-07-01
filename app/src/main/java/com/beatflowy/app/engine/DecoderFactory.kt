package com.beatflowy.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DecoderFactory(
    private val context: Context,
    private val driveAccountRepository: DriveAccountRepository,
    private val cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager,
    private val tdLibManager: com.beatflowy.app.telegram.TdLibManager,
    private val ffmpegAlacDecoder: FfmpegAlacDecoder,
    private val mediaCodecDecoder: MediaCodecAudioDecoder
) {
    suspend fun create(song: Song): AudioDecoder {
        val format = song.format.lowercase()
        val isCloud = song.isCloud()

        val durationMin = song.durationMs / 60000.0
        val sizeMb = song.fileSizeBytes / (1024.0 * 1024.0)
        val isLikelyLossyM4A = (format == "m4a" || format == "mp4" || format == "aac") &&
                ((durationMin > 0 && (sizeMb / durationMin) < 2.3) || (song.bitrate in 1..400000))

        val isAlac = format.contains("alac") ||
                ((format == "m4a" || format == "mp4") && !isLikelyLossyM4A) ||
                song.title.contains("alac", ignoreCase = true)

        val isM4A = format == "m4a" || format == "mp4"
        val isWav = format.contains("wav")
        val isLossless = isAlac || isWav || format.contains("flac") || format.contains("dsd") || format.contains("aiff")

        // 1. Cloud routing
        if (isCloud) {
            // FFmpeg is much more robust for ALAC and WAV (especially over network)
            // Local MediaCodec often fails or has glitches with lossless formats over MediaDataSource.
            // However, for Telegram we now use a specialized MediaDataSource that handles local file growth,
            // which MediaCodec handles better for WAV than FFmpeg does without complex piping.
            if (isAlac || (isWav && song.source != SongSource.TELEGRAM)) {
                Log.i(TAG, "Routing Cloud Lossless (${if (isAlac) "ALAC" else "WAV"}) to FFmpeg: ${song.title}")
                return ffmpegAlacDecoder
            }

            // Always probe for cloud M4A if bitrate is high or unknown, to catch ALAC early
            if (isM4A && (song.bitrate > 500000 || song.bitrate == 0)) {
                val probedMime = probeAudioMime(song)
                if (probedMime?.contains("alac", ignoreCase = true) == true) {
                    Log.i(TAG, "Routing Cloud Probed ALAC to FFmpeg: ${song.title}")
                    return ffmpegAlacDecoder
                }
            }

            // NOTE: we intentionally do NOT add a generic "probe every cloud file with
            // bitrate==0" fallback here. Telegram songs always have bitrate==0 (it's never
            // populated by TelegramChannelRepository), so such a probe would fire on every
            // single non-WAV Telegram song and block on a network read via
            // TelegramFileDataSource before playback could even start. Since format is now
            // derived correctly from the filename/mimeType at scan time (see
            // TelegramChannelRepository.detectAudioFormat), the isAlac/isWav checks above are
            // sufficient without an extra network round-trip per song.

            // Most other cloud files (AAC, FLAC, MP3) should use MediaCodec for better streaming
            Log.d(TAG, "Routing to MediaCodec (Cloud): ${song.title} [format=$format]")
            return mediaCodecDecoder
        }

        // 2. Local routing
        // For local files, FFmpeg is often more stable for ALAC and WAV (especially with seeking).
        if (isAlac || isM4A || isWav || format == "audio") {
            // Check if it's actually ALAC inside M4A container
            val probedMime = if (isM4A) probeAudioMime(song) else null
            if (isAlac || isWav || probedMime?.contains("alac", ignoreCase = true) == true) {
                Log.i(TAG, "Routing to FFmpeg (Local Lossless): ${song.title} [format=$format]")
                return ffmpegAlacDecoder
            }
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
            } else if (song.source != SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(song, tdLibManager) { false }
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
            val inputSource = if (song.source == SongSource.LOCAL) {
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