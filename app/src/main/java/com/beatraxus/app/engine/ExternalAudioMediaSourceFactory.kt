package com.beatraxus.app.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * An audio file the user loaded next to a video (feature 25: "Load an external audio track").
 *
 * @param uri   content:// / file:// / http(s) address of the audio file.
 * @param label name shown in the Audio Tracks sheet (normally the file name without its extension).
 */
data class ExternalAudioTrack(val uri: Uri, val label: String) {

    companion object {
        /**
         * [MergingMediaSource] prefixes every track-group id with the index of the source it came from
         * ("0:" for the video itself, "1:" for the audio we merged in), which is how the loaded audio
         * is told apart from the audio tracks that were already inside the video.
         */
        const val EXTERNAL_GROUP_ID_PREFIX = "1:"

        fun isExternalGroupId(groupId: String?): Boolean = externalIndexOf(groupId) >= 0

        /**
         * Position (0-based) of the loaded audio file a merged track-group id belongs to: "1:..." -> 0, "2:..." -> 1.
         * -1 for the video's own tracks ("0:..." / no prefix). Several audio files can be loaded for one video.
         */
        fun externalIndexOf(groupId: String?): Int {
            if (groupId == null) return -1
            val colon = groupId.indexOf(':')
            if (colon <= 0) return -1
            val source = groupId.substring(0, colon).toIntOrNull() ?: return -1
            return if (source >= 1) source - 1 else -1
        }

        /** "My Dub.2024.mka" -> "My Dub.2024"; blank / null -> "External audio". */
        fun labelFromFileName(fileName: String?): String {
            val name = fileName?.trim().orEmpty()
            if (name.isEmpty()) return DEFAULT_LABEL
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
            return base.ifBlank { DEFAULT_LABEL }
        }

        const val DEFAULT_LABEL = "External audio"
    }
}

/**
 * Wraps the player's normal media-source factory. For a media item whose id has an [ExternalAudioTrack], the video's
 * source is merged with the audio file, so the file shows up as one more selectable audio track and plays in sync
 * with the picture. Every other item is created by [delegate] exactly as before.
 *
 * Everything except [createMediaSource] is forwarded to [delegate] (Kotlin interface delegation), so this keeps
 * working with whatever optional members the Media3 version declares.
 */
@UnstableApi
class ExternalAudioMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val audioDataSourceFactory: DataSource.Factory,
    private val externalAudioFor: (mediaId: String) -> List<ExternalAudioTrack>
) : MediaSource.Factory by delegate {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val videoSource = delegate.createMediaSource(mediaItem)
        val externals = externalAudioFor(mediaItem.mediaId)
        if (externals.isEmpty()) return videoSource
        val audioFactory = ProgressiveMediaSource.Factory(audioDataSourceFactory)
        val audioSources = externals.map { audioFactory.createMediaSource(MediaItem.fromUri(it.uri)) }
        // Order matters: index 0 = video (its own tracks keep the "0:" prefix), index 1.. = the loaded audio files.
        return MergingMediaSource(videoSource, *audioSources.toTypedArray())
    }
}
