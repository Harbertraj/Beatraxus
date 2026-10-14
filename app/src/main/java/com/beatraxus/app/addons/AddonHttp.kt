package com.beatraxus.app.addons

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Small shared HTTP helper for the media-server add-ons (blocking calls — run on Dispatchers.IO).
 */
object AddonHttp {

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).header("User-Agent", "Beatraxus/3")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    /**
     * Same as [getText] but cancels the network call itself when the calling coroutine is cancelled
     * (new search typed, screen left), instead of letting a slow request run to completion in the background.
     */
    suspend fun getTextCancellable(url: String, headers: Map<String, String> = emptyMap()): String =
        suspendCancellableCoroutine { cont ->
            val builder = Request.Builder().url(url).header("User-Agent", "Beatraxus/3")
            headers.forEach { (k, v) -> builder.header(k, v) }
            val call = client.newCall(builder.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    var failure: Throwable? = null
                    var text: String? = null
                    response.use { r ->
                        try {
                            if (!r.isSuccessful) throw HttpStatusException(r.code)
                            text = r.body?.string() ?: throw IOException("Empty response from server")
                        } catch (e: Throwable) {
                            failure = e
                        }
                    }
                    if (!cont.isActive) return
                    val err = failure
                    if (err != null) cont.resumeWithException(err) else cont.resume(text ?: "")
                }
            })
        }

    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Beatraxus/3")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            r.body?.string() ?: throw IOException("Empty response from server")
        }

    /** Turns network/HTTP failures into short, human-readable sign-in errors. */
    fun friendlyMessage(e: Throwable): String = when (e) {
        is HttpStatusException -> when (e.code) {
            401, 403 -> "Wrong username or password"
            404 -> "Server found, but this isn't the right address for it"
            429 -> "Too many requests — wait a moment and try again"
            else -> "Server answered with an error (HTTP ${e.code})"
        }
        is UnknownHostException -> "Can't reach the server — check the address and your internet connection"
        is SocketTimeoutException -> "The server took too long to answer"
        is ConnectException -> "Couldn't connect — check the address and port"
        is SSLException -> "Secure connection failed — try http:// or fix the certificate"
        is IllegalArgumentException -> "That server address isn't valid"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
    }
}
