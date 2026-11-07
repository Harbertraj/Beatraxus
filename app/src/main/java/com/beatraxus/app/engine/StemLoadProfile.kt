package com.beatraxus.app.engine

import android.content.Context

/**
 * How carefully the Max (HTDemucs) model is loaded. Android's low-memory killer leaves no exception, so the only
 * evidence of a native out-of-memory kill is the unfinished-run marker ([StemRunMarker]). Every kill moves the
 * device one level down: a lighter, slower load. After the last level Max is not offered any more.
 *
 *  - level 0: BASIC graph optimisation, 4 threads.
 *  - level 1: no graph optimisation (nothing is constant-folded or copied at load), 2 threads.
 *  - level 2 and up: [isMaxBlocked], Max stays off on this device; Fast still works.
 */
object StemLoadProfile {
    const val MAX_USABLE_LEVEL = 1

    private const val PREFS = "stem_prefs"
    private const val KEY_LEVEL = "max_load_level"

    fun threads(level: Int): Int = if (level <= 0) 4 else 2

    /** True when graph optimisation must be off for this level. */
    fun noGraphOptimisation(level: Int): Boolean = level >= 1

    fun isMaxBlocked(level: Int): Boolean = level > MAX_USABLE_LEVEL

    fun next(level: Int): Int = (level + 1).coerceAtMost(MAX_USABLE_LEVEL + 1)

    fun level(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEVEL, 0)

    /** Records one more kill and returns the new level. */
    fun escalate(context: Context): Int {
        val n = next(level(context))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LEVEL, n).apply()
        return n
    }

    /** Lets the user try Max again at the default level (e.g. after freeing memory or an app update). */
    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LEVEL).apply()
    }
}
