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

    fun register(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) map[url] = headers
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            val h = map[spec.uri.toString()]
            if (h.isNullOrEmpty()) spec else spec.withAdditionalHeaders(h)
        }
        return DefaultDataSource.Factory(context, resolving)
    }
}
