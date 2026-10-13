package com.beatraxus.app.addons

import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.Video
import com.beatraxus.app.viewmodel.PlayerViewModel

object AddonPlaybackHelper {

    suspend fun resolveAndPlay(
        addon: MediaServerAddon,
        item: AddonMediaItem,
        playerViewModel: PlayerViewModel
    ) {
        val streamUrl = addon.streamUrl(item)
        val uri = Uri.parse(streamUrl)
        // Internet Archive items are sometimes mis-typed (a "movie" that is only audio): trust the file.
        val isVideo = if (addon is ArchiveOrgAddon) {
            val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            when (ext) {
                "mp3", "m4a", "ogg", "opus", "flac", "wav", "aac" -> false
                "mp4", "m4v", "webm", "mkv", "mov", "mpeg4", "mpg", "avi", "ogv" -> true
                else -> item.mediaType == "video"
            }
        } else item.mediaType == "video"
        if (isVideo) {
            val video = Video(
                id = "${addon.id}_${item.id}",
                uri = uri,
                title = item.title,
                displayName = item.title,
                folderPath = "",
                durationMs = item.durationMs,
                sizeBytes = 0L,
                resolutionWidth = 0,
                resolutionHeight = 0,
                mimeType = "video/*",
                dateAdded = System.currentTimeMillis()
            )
            playerViewModel.playVideo(video)
        } else {
            val song = Song(
                id = "${addon.id}_${item.id}",
                title = item.title,
                artist = item.subtitle ?: addon.displayName,
                album = addon.displayName,
                durationMs = item.durationMs,
                source = SongSource.WEB,
                uri = uri,
                format = "unknown",
                sampleRateHz = 44100
            )
            playerViewModel.playSong(song)
        }
    }
}
