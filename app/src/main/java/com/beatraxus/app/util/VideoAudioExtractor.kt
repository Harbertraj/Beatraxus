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
object VideoAudioExtractor {

    sealed interface Result {
        data class Saved(val fileName: String) : Result
        data class Failed(val reason: String) : Result
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
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val scheme = source.scheme?.lowercase(Locale.ROOT)
        if (scheme != "content" && scheme != "file" && scheme != "http" && scheme != "https") {
            return@withContext Result.Failed("This video can't be read directly, so its audio can't be extracted")
        }
        val resolver = context.contentResolver
        val extractor = MediaExtractor()
        var docUri: Uri? = null
        var finished = false
        var sourcePfd: ParcelFileDescriptor? = null
        // Speed: copying is I/O bound, so run at a higher priority than the other IO threads.
        val oldPriority = Process.getThreadPriority(Process.myTid())
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
        try {
            try {
                // A real file descriptor lets the extractor read straight from the file (kernel read-ahead);
                // going through the content resolver is several times slower. Network streams use the Uri.
                val local = source.scheme?.lowercase(Locale.ROOT).let { it == "content" || it == "file" }
                sourcePfd = if (local) runCatching { resolver.openFileDescriptor(source, "r") }.getOrNull() else null
                val pfd = sourcePfd
                if (pfd != null) extractor.setDataSource(pfd.fileDescriptor) else extractor.setDataSource(context, source, null)
            } catch (e: Exception) {
                try {
                    extractor.setDataSource(context, source, null)
                } catch (e2: Exception) {
                    return@withContext Result.Failed("Couldn't open the video file")
                }
            }
            val audioTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (audioTracks.isEmpty()) return@withContext Result.Failed("No audio track found in this file")
            val trackIndex = audioTracks[nativeAudioIndex.coerceIn(0, audioTracks.lastIndex)]
            val format = extractor.getTrackFormat(trackIndex)
            val audioMime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val target = targetFor(audioMime)
                ?: return@withContext Result.Failed("This audio format ($audioMime) can't be saved without converting it")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

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
                                val size = extractor.readSampleData(buffer, 0)
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
                            val size = extractor.readSampleData(buffer, 0)
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
            runCatching { extractor.release() }
            runCatching { sourcePfd?.close() }
            runCatching { Process.setThreadPriority(oldPriority) }
            // Never leave a half-written file behind.
            if (!finished) docUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }
}
