package com.beatraxus.app.model

import android.net.Uri

data class VideoFolder(
    val name: String,
    val path: String,
    val videoCount: Int,
    val previewThumbnails: List<Uri>
)
