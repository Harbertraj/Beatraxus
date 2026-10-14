package com.beatraxus.app.addons

import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.Video
import com.beatraxus.app.viewmodel.PlayerViewModel

object AddonPlaybackHelper {

    /**
     * Plays [item] through the app's one player. [browsing] is the list the item was tapped in: when its audio
     * entries can all be played directly, next/previous moves through that list.
     */
    suspend fun resolveAndPlay(
        addon: MediaServerAddon,
        item: AddonMediaItem,
        playerViewModel: PlayerViewModel,
        browsing: List<AddonMediaItem> = emptyList()
    ) {
        // An audio upload that is really an album or concert (Internet Archive) plays as its track list.
        if (item.mediaType == "audio") {
            val tracks = addon.tracks(item)
            if (tracks.isNotEmpty()) {
                playAudio(addon, QueuePlan(tracks, 0), playerViewModel)
                return
            }
        }

        val streamUrl = addon.streamUrl(item)
        val isVideo = isVideoStream(addon is ArchiveOrgAddon, item, streamUrl)
        if (isVideo) {
            val video = Video(
                id = "${addon.id}_${item.id}",
                uri = Uri.parse(streamUrl),
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
            playAudio(addon, planQueue(item, streamUrl, browsing), playerViewModel)
        }
    }

    private fun playAudio(addon: MediaServerAddon, plan: QueuePlan, playerViewModel: PlayerViewModel) {
        val songs = plan.items.map { toSong(songSpec(addon.id, addon.displayName, it, it.directPlayUrl)) }
        if (songs.size == 1) {
            playerViewModel.playSong(songs[0])
        } else {
            playerViewModel.playList(songs, plan.startIndex)
        }
    }

    private fun toSong(spec: SongSpec): Song = Song(
        id = spec.id,
        title = spec.title,
        artist = spec.artist,
        album = spec.album,
        durationMs = spec.durationMs,
        source = SongSource.WEB,
        uri = Uri.parse(spec.url),
        format = "unknown",
        sampleRateHz = 44100,
        albumArtUri = spec.artworkUrl?.let { Uri.parse(it) },
        trackNumber = spec.trackNumber
    )
}
