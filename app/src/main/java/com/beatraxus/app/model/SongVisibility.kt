package com.beatraxus.app.model

/**
 * Audio feature 13: songs the listener does not want in the library.
 *
 * Two independent rules, both applied in one place so every list (all songs, albums, artists, search, home ...) agrees:
 *  - Hidden songs: left out until the listener shows them again.
 *  - Minimum duration: songs shorter than the chosen length (voice notes, ringtones, notification sounds) are left out.
 *    A duration of 0 means "not known yet" (a cloud song that has not been read), and such a song is never left out.
 *
 * Kept free of Android types so it can be unit tested.
 */
object SongVisibility {

    /** Choices offered for the minimum duration, in seconds. 0 = off. */
    val MIN_DURATION_CHOICES_SEC: List<Int> = listOf(0, 15, 30, 60, 120)

    private const val MAX_MIN_DURATION_SEC = 600

    /** Brings a stored value back into a sane range (an old or edited preference must not hide the whole library). */
    fun normalizeMinDurationSec(sec: Int): Int = sec.coerceIn(0, MAX_MIN_DURATION_SEC)

    /** True when the song has a known duration that is below the minimum. A minimum of 0 never filters. */
    fun isTooShort(durationMs: Long, minDurationSec: Int): Boolean {
        val minMs = normalizeMinDurationSec(minDurationSec) * 1000L
        return minMs > 0L && durationMs in 1L until minMs
    }

    fun <T> visible(
        items: List<T>,
        hiddenIds: Set<String>,
        minDurationSec: Int,
        id: (T) -> String,
        durationMs: (T) -> Long
    ): List<T> {
        if (hiddenIds.isEmpty() && normalizeMinDurationSec(minDurationSec) == 0) return items
        return items.filter { !(id(it) in hiddenIds) && !isTooShort(durationMs(it), minDurationSec) }
    }

    /** "Off", "15 sec", "1 min", "2 min" ... for the chips. */
    fun label(sec: Int): String = when {
        sec <= 0 -> "Off"
        sec < 60 -> "$sec sec"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> "${sec / 60} min ${sec % 60} sec"
    }
}
