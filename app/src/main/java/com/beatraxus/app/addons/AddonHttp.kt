package com.beatraxus.app.addons

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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
            else -> "Server answered with an error (HTTP ${e.code})"
        }
        is UnknownHostException -> "Can't find that server — check the address"
        is SocketTimeoutException -> "The server took too long to answer"
        is ConnectException -> "Couldn't connect — check the address and port"
        is SSLException -> "Secure connection failed — try http:// or fix the certificate"
        is IllegalArgumentException -> "That server address isn't valid"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
    }
}
