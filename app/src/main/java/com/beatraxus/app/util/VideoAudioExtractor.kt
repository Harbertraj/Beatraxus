package com.beatraxus.app.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Saves one audio track of a video as its own audio file, WITHOUT re-encoding (the compressed audio is copied as it is,
 * so it is fast and lossless). The file is created in a folder the user picked with the system file manager.
 *
 * Containers: AAC -> .m4a, Opus -> .opus (Android 10+), Vorbis -> .webm; AC-3 / E-AC-3 / DTS / MP3 / FLAC are written
 * as plain streams (.ac3 .eac3 .dts .mp3 .flac), which every audio player opens.
 */
/**
 * Reads the compressed samples of a file. Two implementations: the phone's own [MediaExtractor], and Media3's
 * [MediaExtractorCompat], which uses the same demuxers as the video player.
 *
 * Why both: for some files (Matroska audio stored several frames per block) the phone's extractor hands out a whole block
 * as ONE sample. Written into an .m4a that is one huge "sample" holding ~8 audio frames, and a decoder plays only the
 * first frame of each, so the saved file can't be played back or synced (the player showed "timing invalid"). Media3
 * hands out every frame separately, exactly as the video player itself plays them, so it is tried first.
 */
private interface Demuxer {
    val trackCount: Int
    fun trackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
    /** Size of the sample read into [buffer] (at offset 0), or -1 at the end. */
    fun readSampleData(buffer: ByteBuffer): Int
    val sampleTime: Long
    fun advance()
    fun release()
}

private class PlatformDemuxer(private val e: MediaExtractor) : Demuxer {
    override val trackCount get() = e.trackCount
    override fun trackFormat(index: Int): MediaFormat = e.getTrackFormat(index)
    override fun selectTrack(index: Int) = e.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer): Int = e.readSampleData(buffer, 0)
    override val sampleTime get() = e.sampleTime
    override fun advance() { e.advance() }
    override fun release() = e.release()
}

@UnstableApi
private class Media3Demuxer(private val e: MediaExtractorCompat) : Demuxer {
    override val trackCount get() = e.trackCount
    override fun trackFormat(index: Int): MediaFormat = e.getTrackFormat(index)
    override fun selectTrack(index: Int) = e.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer): Int = e.readSampleData(buffer, 0)
    override val sampleTime get() = e.sampleTime
    override fun advance() { e.advance() }
    override fun release() = e.release()
}

object VideoAudioExtractor {

    sealed interface Result {
        data class Saved(val fileName: String) : Result
        data class Failed(val reason: String) : Result
    }


    private const val TAG = "VideoAudioExtractor"

    /** A Media3 demuxer for [source] when it can read it and finds an audio track, otherwise null. */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun openMedia3(context: Context, source: Uri): Demuxer? {
        return try {
            val compat = MediaExtractorCompat(context)
            try {
                compat.setDataSource(source, 0L)
                val hasAudio = (0 until compat.trackCount).any {
                    compat.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (hasAudio) Media3Demuxer(compat) else { compat.release(); null }
            } catch (e: Exception) {
                runCatching { compat.release() }
                Log.w(TAG, "Media3 couldn't read the file, using the phone's extractor", e)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Media3 extractor not usable, using the phone's extractor", t)
            null
        }
    }

    private enum class Kind { MUXER_MP4, MUXER_OGG, MUXER_WEBM, RAW, RAW_FLAC }

    private class Target(val kind: Kind, val ext: String, val mime: String)

    private fun targetFor(audioMime: String): Target? = when (audioMime.lowercase(Locale.ROOT)) {
        "audio/mp4a-latm" -> Target(Kind.MUXER_MP4, "m4a", "audio/mp4")
        "audio/opus" -> if (Build.VERSION.SDK_INT >= 29) Target(Kind.MUXER_OGG, "opus", "audio/ogg") else null
        "audio/vorbis" -> Target(Kind.MUXER_WEBM, "webm", "audio/webm")
        "audio/ac3" -> Target(Kind.RAW, "ac3", "audio/ac3")
        "audio/eac3" -> Target(Kind.RAW, "eac3", "audio/eac3")
        "audio/vnd.dts", "audio/vnd.dts.hd" -> Target(Kind.RAW, "dts", "audio/vnd.dts")
        "audio/mpeg" -> Target(Kind.RAW, "mp3", "audio/mpeg")
        "audio/flac" -> Target(Kind.RAW_FLAC, "flac", "audio/flac")
        else -> null
    }

    /** "Title" + " - " + "Tamil", with characters that file systems refuse removed. */
    fun buildBaseName(videoTitle: String, languageName: String): String {
        val clean = { s: String -> s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ").replace(Regex("\\s+"), " ").trim() }
        val title = clean(videoTitle).ifBlank { "video" }.take(120)
        val language = clean(languageName).ifBlank { "Unknown" }
        return "$title - $language"
    }

    /** "ta" / "tam" -> "Tamil"; null when the code is missing or unknown. */
    fun languageDisplayName(code: String?): String? {
        if (code.isNullOrBlank() || code.equals("und", true) || code.equals("zxx", true)) return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
    }

    /**
     * @param nativeAudioIndex position of the wanted track among the audio tracks inside the video file.
     * @param treeUri folder picked with ACTION_OPEN_DOCUMENT_TREE.
     * @param onProgress 0f..1f, called from the IO thread.
     */
    suspend fun extract(
        context: Context,
        source: Uri,
        nativeAudioIndex: Int,
        treeUri: Uri,
        baseName: String,
        onProgress: (Float) -> Unit,
        /** Length of the whole video (us), used for the percentage when the audio track itself doesn't report one. */
        fallbackDurationUs: Long = 0L
    ): Result = withContext(Dispatchers.IO) {
        val scheme = source.scheme?.lowercase(Locale.ROOT)
        if (scheme != "content" && scheme != "file" && scheme != "http" && scheme != "https") {
            return@withContext Result.Failed("This video can't be read directly, so its audio can't be extracted")
        }
        val resolver = context.contentResolver
        var opened: Demuxer? = null
        var docUri: Uri? = null
        var finished = false
        var sourcePfd: ParcelFileDescriptor? = null
        // Speed: copying is I/O bound, so run at a higher priority than the other IO threads.
        val oldPriority = Process.getThreadPriority(Process.myTid())
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
        try {
            // First choice: Media3's demuxers (the video player's own), which hand out every audio frame separately.
            opened = openMedia3(context, source)
            if (opened == null) {
                val platform = MediaExtractor()
                opened = PlatformDemuxer(platform)
                try {
                    // A real file descriptor lets the extractor read straight from the file (kernel read-ahead);
                    // going through the content resolver is several times slower. Network streams use the Uri.
                    val local = source.scheme?.lowercase(Locale.ROOT).let { it == "content" || it == "file" }
                    sourcePfd = if (local) runCatching { resolver.openFileDescriptor(source, "r") }.getOrNull() else null
                    val pfd = sourcePfd
                    if (pfd != null) platform.setDataSource(pfd.fileDescriptor) else platform.setDataSource(context, source, null)
                } catch (e: Exception) {
                    try {
                        platform.setDataSource(context, source, null)
                    } catch (e2: Exception) {
                        return@withContext Result.Failed("Couldn't open the video file")
                    }
                }
            }
            val extractor: Demuxer = opened ?: return@withContext Result.Failed("Couldn't open the video file")
            Log.i(TAG, "Extracting with ${if (extractor is PlatformDemuxer) "the phone's MediaExtractor" else "Media3"}")
            val audioTracks = (0 until extractor.trackCount).filter {
                extractor.trackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (audioTracks.isEmpty()) return@withContext Result.Failed("No audio track found in this file")
            val trackIndex = audioTracks[nativeAudioIndex.coerceIn(0, audioTracks.lastIndex)]
            val format = extractor.trackFormat(trackIndex)
            val audioMime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val target = targetFor(audioMime)
                ?: return@withContext Result.Failed("This audio format ($audioMime) can't be saved without converting it")
            // Media3's track formats often carry no duration, which left the percentage stuck at 0%.
            val durationUs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L)
                .takeIf { it > 0L } ?: fallbackDurationUs

            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            docUri = DocumentsContract.createDocument(resolver, parent, target.mime, "$baseName.${target.ext}")
                ?: return@withContext Result.Failed("Couldn't create the file in that folder")

            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 64 * 1024)
                else 1 shl 20
            )
            val info = MediaCodec.BufferInfo()

            // Speed: report progress at most ~10 times a second and only when the percentage moved. Updating the UI
            // state for every audio frame (thousands per second of audio) was a big part of the slowness.
            var lastReportMs = 0L
            var lastPercent = -1
            fun progressOf(timeUs: Long) {
                if (durationUs <= 0) return
                val percent = ((timeUs.toFloat() / durationUs) * 100f).toInt().coerceIn(0, 100)
                if (percent == lastPercent) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportMs < 100L && percent < 100) return
                lastPercent = percent
                lastReportMs = now
                onProgress(percent / 100f)
            }

            when (target.kind) {
                Kind.MUXER_MP4, Kind.MUXER_OGG, Kind.MUXER_WEBM -> {
                    val pfd = resolver.openFileDescriptor(docUri, "rw")
                        ?: return@withContext Result.Failed("Couldn't write to that folder")
                    pfd.use {
                        val outFormat = when (target.kind) {
                            Kind.MUXER_MP4 -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                            Kind.MUXER_OGG -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                        }
                        val muxer = MediaMuxer(it.fileDescriptor, outFormat)
                        var started = false
                        try {
                            val outTrack = muxer.addTrack(format)
                            muxer.start()
                            started = true
                            while (true) {
                                coroutineContext.ensureActive()
                                buffer.clear()
                                val size = extractor.readSampleData(buffer)
                                if (size < 0) break
                                val sampleTime = extractor.sampleTime
                                info.set(0, size, sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                muxer.writeSampleData(outTrack, buffer, info)
                                progressOf(sampleTime)
                                extractor.advance()
                            }
                        } finally {
                            if (started) runCatching { muxer.stop() }
                            runCatching { muxer.release() }
                        }
                    }
                }
                Kind.RAW, Kind.RAW_FLAC -> {
                    // A file descriptor + a big buffer: far fewer write calls than the content resolver's stream.
                    val outPfd = resolver.openFileDescriptor(docUri, "w")
                        ?: return@withContext Result.Failed("Couldn't write to that folder")
                    // Only the ParcelFileDescriptor is closed (closing the stream too would close the same fd twice).
                    outPfd.use {
                        val stream = BufferedOutputStream(FileOutputStream(it.fileDescriptor), 512 * 1024)
                        if (target.kind == Kind.RAW_FLAC) {
                            // FLAC frames need the "fLaC" marker + STREAMINFO in front; the extractor keeps them in csd-0.
                            format.getByteBuffer("csd-0")?.let { csd ->
                                val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                                val hasMarker = bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte()
                                if (!hasMarker) stream.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
                                stream.write(bytes)
                            }
                        }
                        val bytes = buffer.array() // heap buffer: written straight out, no extra copy
                        while (true) {
                            coroutineContext.ensureActive()
                            buffer.clear()
                            val size = extractor.readSampleData(buffer)
                            if (size < 0) break
                            val sampleTime = extractor.sampleTime
                            stream.write(bytes, buffer.arrayOffset(), size)
                            progressOf(sampleTime)
                            extractor.advance()
                        }
                        stream.flush()
                    }
                }
            }
            onProgress(1f)
            finished = true
            Result.Saved("$baseName.${target.ext}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed("Extraction failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { opened?.release() }
            runCatching { sourcePfd?.close() }
            runCatching { Process.setThreadPriority(oldPriority) }
            // Never leave a half-written file behind.
            if (!finished) docUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }
}
