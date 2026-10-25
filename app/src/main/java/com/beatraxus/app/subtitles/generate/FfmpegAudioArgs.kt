package com.beatraxus.app.subtitles.generate

import java.util.Locale

/**
 * The FFmpeg command that cuts one chunk of a video's audio for speech recognition.
 *
 * Kept separate from the code that runs it so the options that decide sync are easy to see and to test:
 *  - `-ss` before `-i` seeks the input and decodes accurately, so the chunk begins at the requested millisecond.
 *  - `aresample=async=1:first_pts=0` pads silence in front when the audio stream starts later than the container
 *    (common in MKV and TS files). Without it the first word would be heard at 0 s in the WAV although it is spoken at,
 *    say, 0.5 s in the video, and every cue of that chunk would be early by the same amount.
 *  - `-map 0:a:N` takes the audio track the viewer is listening to, not just the first one.
 */
object FfmpegAudioArgs {

    fun build(inputSpec: String, startMs: Long, lengthMs: Long, audioStreamOrdinal: Int, outputPath: String): Array<String> {
        val ordinal = audioStreamOrdinal.coerceAtLeast(0)
        return arrayOf(
            "-y", "-v", "error", "-nostdin",
            "-ss", seconds(startMs),
            "-i", inputSpec,
            "-map", "0:a:$ordinal",
            "-vn", "-sn", "-dn",
            "-af", "aresample=async=1:first_pts=0",
            "-ac", "1",
            "-ar", "16000",
            "-c:a", "pcm_s16le",
            "-t", seconds(lengthMs),
            "-f", "wav",
            outputPath
        )
    }

    /** Arguments of ffprobe that print the container duration in seconds and nothing else. */
    fun probeDuration(inputSpec: String): Array<String> = arrayOf(
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        inputSpec
    )

    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms.coerceAtLeast(0L) / 1000.0)

    /** A 16 kHz mono 16-bit WAV is 32,000 bytes per second plus a 44-byte header. */
    const val WAV_BYTES_PER_SECOND = 32_000L
    const val WAV_HEADER_BYTES = 44L

    /** Seconds of audio in a finished WAV chunk of [fileBytes] bytes. */
    fun wavSeconds(fileBytes: Long): Double =
        ((fileBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)).toDouble() / WAV_BYTES_PER_SECOND
}
