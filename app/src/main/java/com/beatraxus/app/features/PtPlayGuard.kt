package com.beatraxus.app.features

/**
 * Decides when a follower that should be playing but is not gets a nudge.
 *
 * A follower can be left on the room's song, loaded and ready, but paused while the room plays (a start
 * that never landed, a pause that arrived after the controller's change, audio focus that was refused for a
 * moment). This is the pure rule behind the manager's check: wait a moment (a start takes time), then try
 * to start it, and if that does not help reload the song once; after that stop trying until the room
 * changes again, so a phone call or another app holding the audio is not fought forever.
 */
internal object PtPlayGuard {
    /** How long the follower may stay paused before the first nudge (starting a song takes a moment). */
    const val GRACE_MS = 700L
    /** Minimum time between two nudges, so each one has time to take effect. */
    const val KICK_GAP_MS = 700L
    /** Plain "start playing" nudges before the song is reloaded. */
    const val START_KICKS = 2
    /** Nudges in total (start, start, reload) before giving up until the room changes. */
    const val MAX_KICKS = 3

    enum class Action { NONE, WAIT, START, RELOAD }

    /**
     * @param shouldPlay the room plays this song and this phone has it loaded (and is not the controller)
     * @param playing the player is playing now
     * @param pausedForMs how long it has been paused while it should play
     * @param sinceLastKickMs time since the last nudge
     * @param kicks nudges so far for the current room state
     */
    fun next(shouldPlay: Boolean, playing: Boolean, pausedForMs: Long, sinceLastKickMs: Long, kicks: Int): Action {
        if (!shouldPlay || playing) return Action.NONE
        if (kicks >= MAX_KICKS) return Action.NONE
        if (pausedForMs < GRACE_MS || sinceLastKickMs < KICK_GAP_MS) return Action.WAIT
        return if (kicks < START_KICKS) Action.START else Action.RELOAD
    }
}
