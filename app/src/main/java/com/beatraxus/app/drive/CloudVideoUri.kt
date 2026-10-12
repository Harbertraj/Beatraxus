package com.beatraxus.app.drive

import android.net.Uri
import com.beatraxus.app.model.VideoSource

/**
 * Playable address of a video that lives in a cloud drive (Google Drive, Dropbox, OneDrive, Box,
 * Nextcloud):
 * `cloudvideo://stream?src=<VideoSource>&acct=<account>&file=<provider file id>&size=<bytes>`
 *
 * It carries no token: [CloudVideoStreamResolver] turns it into a real https address plus fresh
 * auth headers every time the player (or the thumbnail probe) opens it, so saved library entries
 * never go stale when tokens expire.
 */
object CloudVideoUri {
    const val SCHEME = "cloudvideo"

    class Parsed(
        val source: VideoSource,
        /** Account email (Nextcloud: username). */
        val account: String,
        /** Provider file id (Nextcloud: absolute URL of the file). */
        val fileId: String,
        val size: Long
    ) {
        val key: String get() = "${source.name}|$account|$fileId"
    }

    fun build(source: VideoSource, account: String, fileId: String, size: Long): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("stream")
            .appendQueryParameter("src", source.name)
            .appendQueryParameter("acct", account)
            .appendQueryParameter("file", fileId)
            .appendQueryParameter("size", size.toString())
            .build()

    fun isCloudVideo(uri: Uri?): Boolean = uri?.scheme == SCHEME

    fun parse(uri: Uri?): Parsed? {
        if (uri == null || uri.scheme != SCHEME) return null
        val source = try {
            VideoSource.valueOf(uri.getQueryParameter("src") ?: return null)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val account = uri.getQueryParameter("acct") ?: return null
        val file = uri.getQueryParameter("file") ?: return null
        val size = uri.getQueryParameter("size")?.toLongOrNull() ?: -1L
        return Parsed(source, account, file, size)
    }
}
