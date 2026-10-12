package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.InterruptedIOException

/**
 * Lets ExoPlayer play `cloudvideo://` addresses (Google Drive, Dropbox, OneDrive, Box, Nextcloud).
 *
 * Wraps the player's normal [DataSource.Factory]: each time a data source is opened the address is
 * resolved to the real https URL + fresh auth headers (see [CloudVideoStreamResolver]) and handed to
 * the normal HTTP stack, which gives range requests / seeking for free. Every other address passes
 * through untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CloudVideoDataSource {

    fun wrap(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val resolver = CloudVideoStreamResolver.get(context)
        return ResolvingDataSource.Factory(upstream) { spec ->
            val parsed = CloudVideoUri.parse(spec.uri)
            if (parsed == null) {
                spec
            } else {
                val target = try {
                    // Runs on ExoPlayer's loader thread, which is allowed to block.
                    runBlocking(Dispatchers.IO) { resolver.resolve(parsed) }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
                spec.withUri(Uri.parse(target.url)).withAdditionalHeaders(target.headers)
            }
        }
    }
}
