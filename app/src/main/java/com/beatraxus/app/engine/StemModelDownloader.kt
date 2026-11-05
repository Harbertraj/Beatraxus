package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
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

object StemModelDownloader {
    private const val TAG = "StemModelDownloader"
    private const val MODEL_URL = "https://huggingface.co/audiojs/scnet/resolve/main/scnet.int8.onnx"
    private const val EXPECTED_SHA256 = "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845"
    private const val MODEL_FILENAME = "scnet.onnx"
    private const val PART_FILENAME = "scnet.onnx.part"

    private val _state = MutableStateFlow<StemModelDownloadState>(StemModelDownloadState.Idle)
    val state: StateFlow<StemModelDownloadState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var downloadJob: Job? = null
    private val client = OkHttpClient()

    fun download(context: Context) {
        if (downloadJob?.isActive == true) return

        val appContext = context.applicationContext
        val modelsDir = File(appContext.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val partFile = File(modelsDir, PART_FILENAME)
        val targetFile = File(modelsDir, MODEL_FILENAME)

        downloadJob = scope.launch {
            _state.value = StemModelDownloadState.Downloading(0f)
            if (partFile.exists()) partFile.delete()

            try {
                val request = Request.Builder()
                    .url(MODEL_URL)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP download error code ${response.code}")
                    }

                    val body = response.body ?: throw IOException("Empty HTTP response body")
                    val contentLength = body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(16384)
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
                                    _state.value = StemModelDownloadState.Downloading(pct)
                                }
                            }
                            output.flush()
                        }
                    }

                    val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!computedHash.equals(EXPECTED_SHA256, ignoreCase = true)) {
                        partFile.delete()
                        _state.value = StemModelDownloadState.Failed("SHA-256 checksum mismatch")
                        return@launch
                    }

                    if (targetFile.exists()) targetFile.delete()
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }

                    Log.i(TAG, "SCNet model downloaded successfully to ${targetFile.absolutePath}")
                    _state.value = StemModelDownloadState.Done
                }
            } catch (e: CancellationException) {
                if (partFile.exists()) partFile.delete()
                _state.value = StemModelDownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download model: ${e.message}", e)
                if (partFile.exists()) partFile.delete()
                _state.value = StemModelDownloadState.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = StemModelDownloadState.Idle
    }
}
