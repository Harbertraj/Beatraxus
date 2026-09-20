package com.beatraxus.app.subtitles.player

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface SubtitlePlayerController {
    val activeExternalTrackId: StateFlow<String?>
    val activeExternalSubtitleName: StateFlow<String?>
    val isDelaySupported: StateFlow<Boolean>
    val currentDelayMs: StateFlow<Long>

    suspend fun attachExternalSubtitle(
        videoId: String,
        file: File,
        language: String?,
        label: String,
        mimeType: String = MimeTypes.APPLICATION_SUBRIP,
        subtitleId: String
    )

    suspend fun removeExternalSubtitle(videoId: String)
    fun selectEmbeddedTrack(groupIndex: Int)
    fun disableSubtitles()
    fun setSubtitleDelay(videoId: String, delayMs: Long, originalFileProvider: () -> File?)
    fun detachPlayer()
}
