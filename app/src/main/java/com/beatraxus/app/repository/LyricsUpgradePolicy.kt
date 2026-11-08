package com.beatraxus.app.repository

/**
 * Decides which lyrics win when a preload finds something for a song. Plain Kotlin, no Android, so it is tested on the JVM.
 *
 * Priority is Word-by-word > Line-synced > Plain. A result only replaces what the song already has when it is
 * strictly better, so the lyrics view never flickers between two equal results, and lyrics the user picked by hand
 * (saved as [USER_SAVED]) are never replaced.
 */
object LyricsUpgradePolicy {
    /** providerId the repository stores when the user saves or picks lyrics themselves. */
    const val USER_SAVED = "user_saved"

    /** How many songs after the playing one get their lyrics preloaded (the playing song is always included). */
    const val PRELOAD_NEXT = 3

    fun rank(type: LyricsType): Int = when (type) {
        LyricsType.WORD_BY_WORD -> 0
        LyricsType.SYNCED -> 1
        LyricsType.PLAIN -> 2
    }

    /** The best of the types a song already has (memory cache, database, file tag), or null when it has none. */
    fun bestType(vararg types: LyricsType?): LyricsType? = types.filterNotNull().minByOrNull { rank(it) }

    /** True when [incoming] should be applied on top of [existing]. */
    fun shouldApply(existing: LyricsType?, userSaved: Boolean, incoming: LyricsType): Boolean {
        if (userSaved) return false
        if (existing == null) return true
        return rank(incoming) < rank(existing)
    }

    /** Song ids to preload, most important first: the playing song, then the next [next] (no repeats). */
    fun window(currentId: String?, upcomingIds: List<String>, next: Int = PRELOAD_NEXT): List<String> {
        val out = LinkedHashSet<String>()
        if (currentId != null) out += currentId
        for (id in upcomingIds.take(next)) out += id
        return out.toList()
    }
}
