package com.beatflowy.app.repository

import android.content.Context
import android.net.Uri
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════════════════
// LucidaRepository
//
// Implements the reverse-engineered lucida.to download flow:
//   1. POST /api/load   → returns { id }
//   2. GET  /api/fetch/request/{id}  (poll until status == "completed")
//   3. GET  /api/fetch/request/{id}/download  → stream audio bytes
//
// Note: lucida.to has no public API (confirmed on their FAQ/roadmap page).
// This implementation replicates what their web frontend does internally
// and is intended for personal, fair-use downloading only.
// ═══════════════════════════════════════════════════════════════════════════════

private const val LUCIDA_BASE = "https://lucida.to"
private const val POLL_INTERVAL_MS = 1_500L
private const val MAX_POLL_ATTEMPTS = 120          // 3 minutes max

// ─── Request / Response models ─────────────────────────────────────────────────

data class LucidaLoadRequest(
    val url: String,
    val service: String = "qobuz",
    val country: String = "auto",
    val private: Boolean = false,
    val metadata: Boolean = true,
    val format: String = "flac"          // flac | mp3 | wav | ogg | m4a | opus
)

data class LucidaLoadResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("error") val error: String?
)

data class LucidaStatusResponse(
    @SerializedName("status") val status: String?,   // queued | processing | completed | failed
    @SerializedName("message") val message: String?,
    @SerializedName("progress") val progress: Int?,
    @SerializedName("error") val error: String?,
    @SerializedName("filename") val filename: String?
)

sealed class LucidaDownloadResult {
    data class Success(val downloadUrl: String, val filename: String?) : LucidaDownloadResult()
    data class Failed(val reason: String) : LucidaDownloadResult()
    data class Progress(val percent: Int, val message: String) : LucidaDownloadResult()
}

// ─── Repository ────────────────────────────────────────────────────────────────

class LucidaRepository(private val context: Context) {

    private val gson = com.google.gson.Gson()

    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Accept",        "application/json, */*")
                    .header("Accept-Language","en-US,en;q=0.9")
                    .header("Origin",        LUCIDA_BASE)
                    .header("Referer",       "$LUCIDA_BASE/")
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // ── Step 1: Submit URL to lucida ──────────────────────────────────────────
    suspend fun submitUrl(request: LucidaLoadRequest): LucidaLoadResponse =
        withContext(Dispatchers.IO) {
            val json = gson.toJson(request)
            val body = json.toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$LUCIDA_BASE/api/load")
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val rawBody = response.body?.string()
                ?: throw Exception("Empty response from lucida")
            gson.fromJson(rawBody, LucidaLoadResponse::class.java)
        }

    // ── Step 2: Poll for completion ───────────────────────────────────────────
    suspend fun pollStatus(
        requestId: String,
        onProgress: (LucidaStatusResponse) -> Unit
    ): LucidaStatusResponse = withContext(Dispatchers.IO) {
        var attempts = 0
        while (attempts < MAX_POLL_ATTEMPTS) {
            attempts++
            val httpRequest = Request.Builder()
                .url("$LUCIDA_BASE/api/fetch/request/$requestId")
                .get()
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw Exception("Poll HTTP ${response.code}: ${response.message}")
            }
            val rawBody = response.body?.string() ?: throw Exception("Empty poll response")
            val status = gson.fromJson(rawBody, LucidaStatusResponse::class.java)

            when (status.status) {
                "completed" -> return@withContext status
                "failed"    -> throw Exception(status.error ?: "Lucida processing failed")
                else        -> {
                    onProgress(status)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        throw Exception("Download timed out after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s")
    }

    // ── Step 3: Build the download URL (caller uses DownloadManager) ──────────
    fun getDownloadUrl(requestId: String): String =
        "$LUCIDA_BASE/api/fetch/request/$requestId/download"

    // ── Convenience: full flow returning (downloadUrl, filename) ─────────────
    suspend fun resolveDownload(
        streamingUrl: String,
        service: String = "qobuz",
        format: String = "flac",
        country: String = "auto",
        private: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        // 1. Submit
        val loadResp = submitUrl(
            LucidaLoadRequest(
                url = streamingUrl,
                service = service,
                country = country,
                private = private,
                metadata = true,
                format = format
            )
        )
        val id = loadResp.id
            ?: throw Exception(loadResp.error ?: "No request ID from lucida")

        // 2. Poll
        val finalStatus = pollStatus(id) { status ->
            val pct  = status.progress ?: 0
            val msg  = status.message ?: "Processing…"
            onProgress(pct, msg)
        }

        // 3. Return download URL + filename
        Pair(getDownloadUrl(id), finalStatus.filename)
    }

    // ── Search: build the URL lucida.to would load ────────────────────────────
    // lucida.to is a web-only interface with no search API; the WebView in
    // DownloadScreen handles actual search. This helper builds the URL to
    // navigate to a specific track/album from a known streaming URL.
    fun buildLucidaUrl(streamingUrl: String): String =
        "$LUCIDA_BASE/?url=${Uri.encode(streamingUrl)}"
}
