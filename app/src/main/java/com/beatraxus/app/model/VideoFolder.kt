package com.beatraxus.app.model

import android.net.Uri

data class VideoFolder(
    val name: String,
    val path: String,
    val videoCount: Int,
    val previewThumbnails: List<Uri>,
    /** Sum of the sizes of the videos in the folder; 0 when unknown. Used to sort folders by size. */
    val totalSizeBytes: Long = 0L,
    /** Sum of the durations of the videos in the folder; 0 when unknown. Used to sort folders by duration. */
    val totalDurationMs: Long = 0L,
    /** Newest "date added" among the videos in the folder; 0 when unknown. Used to sort folders by date. */
    val latestDateAdded: Long = 0L
)
