package com.beatraxus.app.subtitles.domain

/**
 * Video feature 20: the arithmetic behind "tap the line you are hearing to sync the subtitles".
 *
 * All cue times here are the ones in the subtitle file itself, before any delay is applied, because a delay is always
 * applied on top of the original file.
 */
object SubtitleTapSync {

    /**
     * The delay that makes [cue] appear exactly at [playerPositionMs]. Positive means the subtitles were early and
     * must be pushed later, negative means they were late.
     */
    fun delayFor(cue: SubtitleCue, playerPositionMs: Long): Long = playerPositionMs - cue.startMs

    /**
     * Index of the cue that is on screen at [positionMs] or, between cues, the last one that has started. The tap list
     * opens scrolled here, since that is where the line the viewer is hearing most likely is. Returns 0 before the
     * first cue and -1 when there are no cues. [cues] must be sorted by start time, as the parser returns them.
     */
    fun indexNear(cues: List<SubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found < 0) 0 else found
    }

    /**
     * [cues] moved later by [delayMs] (earlier when negative). A cue pushed fully before the start of the video is
     * dropped, and one that only starts before it is cut to begin at zero, the same way a delayed file is written.
     */
    fun shift(cues: List<SubtitleCue>, delayMs: Long): List<SubtitleCue> {
        if (delayMs == 0L) return cues
        return cues.mapNotNull { cue ->
            val end = cue.endMs + delayMs
            if (end <= 0L) null else cue.copy(startMs = (cue.startMs + delayMs).coerceAtLeast(0L), endMs = end)
        }
    }
}
