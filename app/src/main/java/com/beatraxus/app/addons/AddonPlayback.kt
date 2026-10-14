package com.beatraxus.app.addons

/**
 * Pure planning for what gets handed to the app's single player (no Android classes, so it is unit-testable).
 * [AddonPlaybackHelper] turns these plain values into `Song`s and calls the existing PlayerViewModel.
 */

/** Everything the player needs to know about one add-on track. */
internal data class SongSpec(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val url: String,
    val artworkUrl: String?,
    val trackNumber: Int?
)

/** Items to queue and which one to start with. */
internal data class QueuePlan(val items: List<AddonMediaItem>, val startIndex: Int)

/**
 * Add-ons that fill in artist/album get them shown; the others keep the previous behaviour
 * (the "Artist · Album" subtitle as the artist, the add-on's name as the album).
 */
internal fun songSpec(addonId: String, addonName: String, item: AddonMediaItem, url: String): SongSpec = SongSpec(
    id = "${addonId}_${item.id}",
    title = item.title,
    artist = item.artist?.takeIf { it.isNotBlank() } ?: item.subtitle ?: addonName,
    album = item.album?.takeIf { it.isNotBlank() } ?: addonName,
    durationMs = item.durationMs,
    url = url,
    artworkUrl = item.artworkUrl?.takeIf { it.isNotBlank() },
    trackNumber = item.trackNumber
)

/**
 * Tapping a track in a list should let next/previous move through that list, as in any music app. That only works
 * when every audio neighbour already carries its own stream URL (Jamendo, Internet Archive tracks); otherwise —
 * Subsonic and friends, which resolve a URL per item — only the tapped track plays, exactly as before.
 * [resolvedUrl] is the freshly resolved URL of the tapped item and replaces its (possibly stale) listed one.
 */
internal fun planQueue(item: AddonMediaItem, resolvedUrl: String, browsing: List<AddonMediaItem>): QueuePlan {
    val playable = browsing.filter { it.mediaType == "audio" && it.directPlayUrl.isNotBlank() }
    val index = playable.indexOfFirst { it.id == item.id }
    if (index < 0 || playable.size < 2) return QueuePlan(listOf(item.copy(directPlayUrl = resolvedUrl)), 0)
    val items = playable.mapIndexed { i, entry -> if (i == index) entry.copy(directPlayUrl = resolvedUrl) else entry }
    return QueuePlan(items, index)
}

private val ARCHIVE_AUDIO_EXT = setOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")
private val ARCHIVE_VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "mpeg4", "mpg", "avi", "ogv")

/**
 * Internet Archive items are sometimes mis-typed (a "movie" that is only audio): trust the file that was picked.
 * Every other add-on trusts the item's own type.
 */
internal fun isVideoStream(fromArchive: Boolean, item: AddonMediaItem, streamUrl: String): Boolean {
    if (!fromArchive) return item.mediaType == "video"
    val ext = streamUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when (ext) {
        in ARCHIVE_AUDIO_EXT -> false
        in ARCHIVE_VIDEO_EXT -> true
        else -> item.mediaType == "video"
    }
}
