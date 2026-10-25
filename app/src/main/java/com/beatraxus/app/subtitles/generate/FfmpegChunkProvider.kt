package com.beatraxus.app.subtitles.generate

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cuts chunks of one audio track of a video into WAV files for speech recognition, using the FFmpeg build the app
 * already ships. Every chunk is cut at an exact offset of the video's timeline (see [FfmpegAudioArgs]).
 */
class FfmpegChunkProvider(
    private val context: Context,
    private val source: Uri,
    /** Position of the audio track among the video's audio tracks (0 = first). */
    private val audioOrdinal: Int,
    private val workDir: File
) : AudioChunkProvider {

    private val input: String by lazy { FFmpegKitConfig.getSafParameterForRead(context, source) }

    /** Length of the video in milliseconds, or 0 when FFprobe cannot tell. */
    suspend fun durationMs(): Long = withContext(Dispatchers.IO) {
        val session = FFprobeKit.executeWithArguments(FfmpegAudioArgs.probeDuration(input))
        if (!ReturnCode.isSuccess(session.returnCode)) return@withContext 0L
        session.output.orEmpty().trim().toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
    }

    override suspend fun extract(startMs: Long, lengthMs: Long): File? = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val out = File(workDir, "chunk_${startMs}_${System.nanoTime()}.wav")
        val session = FFmpegKit.executeWithArguments(
            FfmpegAudioArgs.build(input, startMs, lengthMs, audioOrdinal, out.absolutePath)
        )
        val ok = ReturnCode.isSuccess(session.returnCode)
        if (!ok) Log.w(TAG, "FFmpeg failed for chunk at ${startMs}ms (audio track $audioOrdinal): rc=${session.returnCode} ${session.allLogsAsString.orEmpty().takeLast(600)}")
        // FFmpeg writes a header-only file when the start is past the end of the audio: that is the end of the video.
        if (!ok || !out.exists() || FfmpegAudioArgs.wavSeconds(out.length()) <= 0.0) {
            out.delete()
            if (!ok && !isEndOfAudio(session.output.orEmpty())) {
                throw GenerationException(GenerationException.Kind.UNSUPPORTED_SOURCE, "Could not read the audio track")
            }
            return@withContext null
        }
        out
    }

    private companion object {
        const val TAG = "SubtitleGen"
    }

    private fun isEndOfAudio(log: String): Boolean =
        log.contains("does not contain any stream", ignoreCase = true) || log.contains("Output file is empty", ignoreCase = true)
}
