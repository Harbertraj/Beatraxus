package com.beatraxus.app.model

/**
 * File formats that are video containers. Cloud scanners keep the file extension as the song's format, so a movie
 * that a cloud account returned shows up here as "MKV", "MP4" ... Local audio never has these formats (the local
 * scanner reports an audio-only MP4 as "AAC").
 */
private val VIDEO_FORMATS = setOf(
    "MKV", "MP4", "M4V", "WEBM", "AVI", "MOV", "WMV", "FLV", "3GP", "3G2", "TS", "M2TS", "MTS", "MPG", "MPEG", "VOB", "OGV", "RM", "RMVB"
)

/** True for a video file (or the temporary entry made when a video plays in the background), so audio-only lists can leave it out. */
val Song.isVideoFile: Boolean
    get() = isFromVideo || videoUri != null || id.startsWith("video_") ||
        format.trim().uppercase().removePrefix(".") in VIDEO_FORMATS ||
        format.startsWith("video/", ignoreCase = true)
