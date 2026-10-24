package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.LyricsType

/**
 * Decides when the provider race for the *playing* song can stop waiting. Providers all run at the
 * same time; results are looked at in the user's priority order.
 *
 *  - a valid word-by-word result wins immediately (nothing can beat it) - the rest are cancelled;
 *  - once a valid timed (word/line) result is in hand, higher-priority providers still running get
 *    only [GRACE_MS] more to deliver a word-synced one, instead of the old "wait for everybody
 *    (up to 15 s)";
 *  - with nothing usable yet we keep waiting for the next provider until the overall deadline.
 */
object LyricsRacePolicy {
    const val GRACE_MS = 1_500L

    fun isDecisive(type: LyricsType, valid: Boolean): Boolean = valid && type == LyricsType.WORD_BY_WORD

    fun isTimed(type: LyricsType): Boolean = type == LyricsType.WORD_BY_WORD || type == LyricsType.SYNCED

    /** How long to wait for the next provider's answer. */
    fun waitMs(haveValidTimed: Boolean, remainingOverallMs: Long): Long {
        val remaining = remainingOverallMs.coerceAtLeast(0L)
        return if (haveValidTimed) minOf(GRACE_MS, remaining) else remaining
    }
}
