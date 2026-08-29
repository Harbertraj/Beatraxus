package com.beatraxus.app.model

import android.net.Uri

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
    val isHdr: Boolean = false
)
