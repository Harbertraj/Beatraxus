package com.beatraxus.app.repository.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException

data class HttpResult(val code: Int, val body: String?)

object LyricsHttp {
    suspend fun get(
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8000
    ): HttpResult = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val req = reqBuilder.build()

        try {
            withTimeout(timeoutMs) {
                LyricsHttpClient.client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    when {
                        code == 404 || code == 401 || code == 204 || code == 422 -> HttpResult(code, null)
                        code == 429 || code >= 500 -> throw LyricsTransientException("HTTP $code")
                        resp.isSuccessful -> HttpResult(code, resp.body?.string())
                        else -> HttpResult(code, null)
                    }
                }
            }
        } catch (e: LyricsTransientException) {
            throw e
        } catch (e: IOException) {
            throw LyricsTransientException(e.message, e)
        } catch (e: TimeoutCancellationException) {
            throw LyricsTransientException("Request timed out ($timeoutMs ms)", e)
        }
    }
}
