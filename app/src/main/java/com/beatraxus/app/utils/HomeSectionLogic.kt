package com.beatraxus.app.utils

import java.util.Locale
import kotlin.random.Random

/**
 * Pure (Android-free) logic behind the extra audio-home sections that sit under "Browse by Mood".
 * Everything is generic over the item type so it can be unit-tested on a plain JVM and reused with Song.
 */
object HomeSectionKeys {
    const val HI_RES_VAULT = "HI_RES_VAULT"
    const val DECADES = "DECADES"
    const val GENRES = "GENRES"
    const val REDISCOVER = "REDISCOVER"

    /** In the order they appear on the home screen, directly after the MOODS section. */
    val NEW_SECTIONS = listOf(HI_RES_VAULT, DECADES, GENRES, REDISCOVER)

    const val ANCHOR = "MOODS"

    /**
     * Saved home-screen orders were written before these sections existed, so they would never show.
     * Inserts any missing new section right after the anchor (keeping their relative order), or at
     * the end if the anchor isn't in the saved order. Everything else is left exactly as saved.
     */
    fun mergeNewSections(
        saved: List<String>,
        newSections: List<String> = NEW_SECTIONS,
        anchor: String = ANCHOR
    ): List<String> {
        val missing = newSections.filter { it !in saved }
        if (missing.isEmpty()) return saved
        val result = saved.toMutableList()
        val at = result.indexOf(anchor)
        if (at >= 0) result.addAll(at + 1, missing) else result.addAll(missing)
        return result
    }
}

// ─────────────────────────────── Hi-Res Vault ───────────────────────────────

/** Hi-res tracks (as decided by [isHiRes]), best first: highest sample rate, then deepest bit depth, then title. */
fun <T> hiResVault(
    items: List<T>,
    isHiRes: (T) -> Boolean,
    sampleRate: (T) -> Int,
    bitDepth: (T) -> Int,
    title: (T) -> String,
    limit: Int = 24
): List<T> = items.filter(isHiRes)
    .sortedWith(
        compareByDescending<T> { sampleRate(it) }
            .thenByDescending { bitDepth(it) }
            .thenBy { title(it).lowercase(Locale.ROOT) }
    )
    .take(limit)

/** Compact quality badge: "24/96", "24/88.2", "16/44.1". Empty when nothing is known. */
fun hiResBadge(bitDepth: Int, sampleRateHz: Int): String {
    val khz = when {
        sampleRateHz <= 0 -> null
        sampleRateHz % 1000 == 0 -> (sampleRateHz / 1000).toString()
        else -> String.format(Locale.US, "%.1f", sampleRateHz / 1000f)
    }
    return when {
        bitDepth > 0 && khz != null -> "$bitDepth/$khz"
        khz != null -> "$khz kHz"
        bitDepth > 0 -> "$bitDepth-bit"
        else -> ""
    }
}

// ───────────────────────────── Through the Decades ──────────────────────────

data class DecadeBucket<T>(val startYear: Int, val label: String, val items: List<T>)

/** 1994 -> 1990, 2003 -> 2000. Null for missing/implausible years (0, 9999 …). */
fun decadeStart(year: Int, maxYear: Int = 2100): Int? =
    if (year in 1900..maxYear) year / 10 * 10 else null

/** 1990 -> "90s", 1960 -> "60s", 2000 -> "2000s", 2010 -> "2010s". */
fun decadeLabel(startYear: Int): String =
    if (startYear in 1900..1999) "${startYear % 100}s" else "${startYear}s"

/** Songs grouped by decade, oldest first. Decades with fewer than [minCount] songs are left out. */
fun <T> byDecade(items: List<T>, year: (T) -> Int, minCount: Int = 3): List<DecadeBucket<T>> =
    items.groupBy { decadeStart(year(it)) }
        .filterKeys { it != null }
        .map { (start, songs) -> DecadeBucket(start!!, decadeLabel(start), songs) }
        .filter { it.items.size >= minCount }
        .sortedBy { it.startYear }

// ───────────────────────────── Genre Spotlight ──────────────────────────────

data class GenreBucket<T>(val name: String, val items: List<T>)

private val UNKNOWN_GENRES = setOf("", "unknown", "<unknown>", "unknown genre", "other", "none", "genre")

/** Splits tag values like "Pop; Rock" into clean genre names. */
fun splitGenres(raw: String): List<String> =
    raw.split(';', '\u0000').map { it.trim() }.filter { it.lowercase(Locale.ROOT) !in UNKNOWN_GENRES }

/**
 * Most common genres first. Case-insensitive grouping ("rock" + "Rock"), shown with its most common
 * spelling; unknown/blank genres are ignored; a song with several genres counts toward each.
 */
fun <T> topGenres(
    items: List<T>,
    genre: (T) -> String,
    limit: Int = 10,
    minCount: Int = 3
): List<GenreBucket<T>> {
    val groups = LinkedHashMap<String, MutableList<T>>()
    val spellings = HashMap<String, MutableMap<String, Int>>()
    for (item in items) {
        for (g in splitGenres(genre(item)).distinctBy { it.lowercase(Locale.ROOT) }) {
            val key = g.lowercase(Locale.ROOT)
            groups.getOrPut(key) { mutableListOf() }.add(item)
            spellings.getOrPut(key) { HashMap() }.merge(g, 1, Int::plus)
        }
    }
    return groups.entries
        .filter { it.value.size >= minCount }
        .map { (key, songs) ->
            val name = spellings.getValue(key).entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
            GenreBucket(name, songs)
        }
        .sortedWith(compareByDescending<GenreBucket<T>> { it.items.size }.thenBy { it.name.lowercase(Locale.ROOT) })
        .take(limit)
}

// ─────────────────────────────── Rediscover ─────────────────────────────────

/**
 * Songs that aren't in [recentIds], in a shuffle that stays the same for a whole day (pass the epoch
 * day as [seed]) so the row doesn't reshuffle every time the screen recomposes.
 */
fun <T> rediscover(
    items: List<T>,
    id: (T) -> String,
    recentIds: Set<String>,
    seed: Long,
    limit: Int = 16
): List<T> = items.filter { id(it) !in recentIds }
    .sortedBy { id(it) } // stable base order so the seeded shuffle is reproducible
    .shuffled(Random(seed))
    .take(limit)
