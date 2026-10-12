package com.beatraxus.app.features

/**
 * Which songs a follower should fetch ahead of time from the controller.
 *
 * Pure functions on song signatures (see PlayTogetherManager.songSig), so the rules are easy to
 * test: the next [COUNT] songs after the one that is playing, fetched in parallel (play order decides who starts first).
 */
internal object PtPreloadPlan {
    /** How many songs ahead of the current one are fetched. */
    const val COUNT = 5

    /** The songs after [currentSig] in the room's queue, in play order, without repeats (at most [count]). */
    fun upcoming(queueSigs: List<String>, currentSig: String?, count: Int = COUNT): List<String> {
        if (currentSig == null) return emptyList()
        val i = queueSigs.indexOf(currentSig)
        if (i < 0) return emptyList()
        return queueSigs.drop(i + 1).filter { it != currentSig }.distinct().take(count)
    }

    /** The first upcoming song this phone does not have yet, or null when everything is in place. */
    fun next(upcoming: List<String>, have: (String) -> Boolean): String? = upcoming.firstOrNull { !have(it) }

    /** Up to [limit] upcoming songs this phone does not have yet (and is not already fetching), in play order. */
    fun missing(upcoming: List<String>, limit: Int, have: (String) -> Boolean): List<String> =
        upcoming.filter { !have(it) }.take(limit.coerceAtLeast(0))

    /**
     * Songs whose received files are worth keeping: the previous song (so going back is instant),
     * the current one and the upcoming ones. Everything else can be deleted from the cache.
     */
    fun keep(queueSigs: List<String>, currentSig: String?, count: Int = COUNT): Set<String> {
        if (currentSig == null) return emptySet()
        val i = queueSigs.indexOf(currentSig)
        val previous = if (i > 0) listOf(queueSigs[i - 1]) else emptyList()
        return (previous + currentSig + upcoming(queueSigs, currentSig, count)).toSet()
    }
}
