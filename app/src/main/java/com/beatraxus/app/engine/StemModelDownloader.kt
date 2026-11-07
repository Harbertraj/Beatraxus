package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import com.beatraxus.app.model.StemQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

sealed interface StemModelDownloadState {
    object Idle : StemModelDownloadState
    data class Downloading(val progress: Float) : StemModelDownloadState
    object Done : StemModelDownloadState
    data class Failed(val errorText: String) : StemModelDownloadState
}

/** One downloadable separation model. [sha256] null = not pinned yet (see the Max entry). */
data class StemModelFile(
    val quality: StemQuality,
    val url: String,
    val fileName: String,
    val sha256: String?,
    /** Smallest size a complete file can have; a shorter download is rejected. */
    val minBytes: Long,
    /** Shown to the user before the download, e.g. "136 MB". Approximate. */
    val approxSizeLabel: String
)

object StemModelDownloader {
    private const val TAG = "StemModelDownloader"

    val FAST_MODEL = StemModelFile(
        quality = StemQuality.FAST,
        url = "https://huggingface.co/audiojs/scnet/resolve/main/scnet.int8.onnx",
        fileName = "scnet.onnx",
        sha256 = "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845",
        minBytes = 1_000_000L,
        approxSizeLabel = "13 MB"
    )

    /**
     * htdemucs_6s with fp16 weights, MIT licence, from the StemSplitio export (input `mix` [1,2,343980], output
     * `stems` [1,6,2,343980], order drums, bass, other, vocals, guitar, piano, per its model card).
     * The SHA-256 is not pinned: it could not be read from here. The file is checked by size and, more strictly, by
     * the tensor contract when it is loaded ([HtDemucsSeparator]). The computed hash is logged after a download
     * (tag StemModelDownloader) so it can be pinned here.
     */
    val MAX_MODEL = StemModelFile(
        quality = StemQuality.MAX,
        url = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx",
        fileName = HtDemucsSeparator.MODEL_FILENAME,
        sha256 = null,
        minBytes = 100_000_000L,
        approxSizeLabel = "136 MB"
    )

    fun modelFor(quality: StemQuality): StemModelFile = when (quality) {
        StemQuality.FAST -> FAST_MODEL
        StemQuality.MAX -> MAX_MODEL
    }

    private val states = StemQuality.entries.associateWith { MutableStateFlow<StemModelDownloadState>(StemModelDownloadState.Idle) }
    private val jobs = HashMap<StemQuality, Job?>()

    /** Never null: one flow per quality. */
    fun stateFor(quality: StemQuality): StateFlow<StemModelDownloadState> = states.getValue(quality).asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient()

    @Synchronized
    fun download(context: Context, quality: StemQuality = StemQuality.FAST) {
        if (jobs[quality]?.isActive == true) return
        val model = modelFor(quality)
        val state = states.getValue(quality)

        val appContext = context.applicationContext
        val modelsDir = File(appContext.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val partFile = File(modelsDir, model.fileName + ".part")
        val targetFile = File(modelsDir, model.fileName)

        jobs[quality] = scope.launch {
            state.value = StemModelDownloadState.Downloading(0f)
            if (partFile.exists()) partFile.delete()

            try {
                val request = Request.Builder().url(model.url).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP download error code ${response.code}")
                    }

                    val body = response.body ?: throw IOException("Empty HTTP response body")
                    val contentLength = body.contentLength()
                    // Files are small enough that "free space" is the common failure: say so before filling the disk.
                    val need = if (contentLength > 0) contentLength else model.minBytes
                    if (modelsDir.usableSpace < need + 50L * 1024 * 1024) {
                        throw IOException("Not enough free storage for the ${model.approxSizeLabel} model")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var totalBytesRead = 0L

                            while (true) {
                                if (!isActive) throw CancellationException("Download cancelled")
                                val read = input.read(buffer)
                                if (read == -1) break

                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                totalBytesRead += read

                                if (contentLength > 0) {
                                    val pct = (totalBytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                                    state.value = StemModelDownloadState.Downloading(pct)
                                }
                            }
                            output.flush()
                        }
                    }

                    val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    val expected = model.sha256
                    if (expected != null && !computedHash.equals(expected, ignoreCase = true)) {
                        partFile.delete()
                        state.value = StemModelDownloadState.Failed("SHA-256 checksum mismatch")
                        return@launch
                    }
                    if (partFile.length() < model.minBytes || (contentLength > 0 && partFile.length() != contentLength)) {
                        val got = partFile.length()
                        partFile.delete()
                        state.value = StemModelDownloadState.Failed("Incomplete download ($got bytes)")
                        return@launch
                    }
                    if (expected == null) Log.i(TAG, "${model.fileName} downloaded, SHA-256 = $computedHash (not pinned)")

                    if (targetFile.exists()) targetFile.delete()
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }

                    Log.i(TAG, "${quality.name} model downloaded successfully to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    state.value = StemModelDownloadState.Done
                }
            } catch (e: CancellationException) {
                if (partFile.exists()) partFile.delete()
                state.value = StemModelDownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download ${quality.name} model: ${e.message}", e)
                if (partFile.exists()) partFile.delete()
                state.value = StemModelDownloadState.Failed(e.message ?: "Download failed")
            }
        }
    }

    @Synchronized
    fun cancel(quality: StemQuality = StemQuality.FAST) {
        jobs[quality]?.cancel()
        jobs[quality] = null
        states.getValue(quality).value = StemModelDownloadState.Idle
    }
}
