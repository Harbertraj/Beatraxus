package com.beatraxus.app.addons

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-URL HTTP request headers for addon streams (Stremio `behaviorHints.proxyHeaders.request`).
 * The video player's data source adds them automatically; URLs without an entry behave exactly as before.
 */
object AddonStreamHeaders {
    private val map = ConcurrentHashMap<String, Map<String, String>>()
    private val hostMap = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) map[url] = headers
    }

    /**
     * Headers for every request to [host]. HLS streams fetch their segments from addresses other than the playlist
     * URL, and a stream that wants a Referer / User-Agent wants it on those requests too (Live TV channels).
     */
    fun registerHost(host: String, headers: Map<String, String>) {
        if (host.isNotBlank() && headers.isNotEmpty()) hostMap[host.lowercase()] = headers
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            val h = map[spec.uri.toString()] ?: spec.uri.host?.let { hostMap[it.lowercase()] }
            if (h.isNullOrEmpty()) spec else spec.withAdditionalHeaders(h)
        }
        return DefaultDataSource.Factory(context, resolving)
    }
}
