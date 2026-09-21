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
        playerViewModel: PlayerViewModel,
        onNavigateToVideoPlayer: (String) -> Unit
    ) {
        val streamUrl = addon.streamUrl(item)
        val uri = Uri.parse(streamUrl)
        if (item.mediaType == "video") {
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
            onNavigateToVideoPlayer(video.id)
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
