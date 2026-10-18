package com.beatraxus.app.model

import android.net.Uri

/**
 * Where a video lives. LOCAL = on-device (MediaStore); everything else is streamed. LIVE_TV marks an IPTV channel
 * (see the livetv package): it is streamed like a cloud video, never scanned, and never saved to watch history.
 */
enum class VideoSource { LOCAL, TELEGRAM, GDRIVE, ONEDRIVE, DROPBOX, BOX, NEXTCLOUD, LIVE_TV }

data class Video(
    val id: String,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val folderPath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val mimeType: String,
    val dateAdded: Long,
    val thumbnailUri: Uri? = null,
    val posterUri: Uri? = null,
    val isHdr: Boolean = false,
    /** Origin of the video. Cloud videos are streamed, so [uri] is not a file/content Uri. */
    val source: VideoSource = VideoSource.LOCAL,
    /** Cloud source identifier, e.g. the Telegram channel URL the video came from. */
    val sourceId: String? = null
) {
    val isCloud: Boolean get() = source != VideoSource.LOCAL
}
