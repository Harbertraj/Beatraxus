package com.beatraxus.app.engine.smart

import java.util.Locale

/** Groups songs by their AI-cleaned genres and sound styles, for the Genres library and the home row. */
object LibraryGrouping {

    class Group<T>(val label: String, val items: List<T>, val isStyle: Boolean)

    fun isStyleLabel(label: String) = label.startsWith(SongProfiler.STYLE_PREFIX)

    /**
     * All groups: real genres first (A-Z, "Unknown" last), then the AI styles (A-Z) that have at least
     * [minStyleSize] songs. A song appears in every group it belongs to.
     */
    fun <T> byLabels(items: List<T>, profileOf: (T) -> SongProfile, minStyleSize: Int = 3): List<Group<T>> {
        val map = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val p = profileOf(item)
            for (l in p.labels) map.getOrPut(l) { ArrayList() }.add(item)
        }
        val genres = map.filterKeys { !isStyleLabel(it) }.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<T>>> { it.key == GenreTaxonomy.UNKNOWN }
                .thenBy { it.key.lowercase(Locale.ROOT) })
            .map { Group(it.key, it.value, false) }
        val styles = map.filterKeys { isStyleLabel(it) }.entries
            .filter { it.value.size >= minStyleSize }
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { Group(it.key, it.value, true) }
        return genres + styles
    }

    /** Biggest groups first (no "Unknown"), for the "Dive into a Genre" row on the home screen. */
    fun <T> topGroups(
        items: List<T>,
        profileOf: (T) -> SongProfile,
        limit: Int = 10,
        minCount: Int = 3
    ): List<Group<T>> =
        byLabels(items, profileOf, minCount)
            .filter { it.label != GenreTaxonomy.UNKNOWN && it.items.size >= minCount }
            .sortedWith(compareByDescending<Group<T>> { it.items.size }.thenBy { it.label.lowercase(Locale.ROOT) })
            .take(limit)

    /** True when [profile] belongs to the group named [label] (a genre, or "✦ Style"). */
    fun matches(profile: SongProfile, label: String): Boolean = profile.labels.contains(label)
}
