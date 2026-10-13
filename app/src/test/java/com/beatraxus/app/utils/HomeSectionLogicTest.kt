package com.beatraxus.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class T(
    val id: String,
    val title: String = id,
    val year: Int = 0,
    val genre: String = "",
    val rate: Int = 44100,
    val bits: Int = 16
)

class HomeSectionKeysTest {

    private val legacy = listOf(
        "GREETING", "ACTION_CHIPS", "CLOUD_LIBRARY", "MOODS",
        "MADE_FOR_YOU", "LISTEN_AGAIN", "YOUR_PLAYLISTS"
    )

    @Test fun newSectionsLandRightAfterMoods() {
        assertEquals(
            listOf(
                "GREETING", "ACTION_CHIPS", "CLOUD_LIBRARY", "MOODS",
                "GENRES", "DECADES", "HI_RES_VAULT", "REDISCOVER",
                "MADE_FOR_YOU", "LISTEN_AGAIN", "YOUR_PLAYLISTS"
            ),
            HomeSectionKeys.mergeNewSections(legacy)
        )
    }

    @Test fun alreadyMigratedOrderIsUntouched() {
        val migrated = HomeSectionKeys.mergeNewSections(legacy)
        assertEquals(migrated, HomeSectionKeys.mergeNewSections(migrated))
    }

    @Test fun customOrderIsRespected() {
        // The user dragged MOODS to the very end: the new sections follow it, nothing else moves.
        val custom = listOf("GREETING", "MADE_FOR_YOU", "MOODS")
        assertEquals(
            listOf("GREETING", "MADE_FOR_YOU", "MOODS", "GENRES", "DECADES", "HI_RES_VAULT", "REDISCOVER"),
            HomeSectionKeys.mergeNewSections(custom)
        )
    }

    @Test fun onlyMissingSectionsAreAdded() {
        val partial = listOf("MOODS", "DECADES", "LISTEN_AGAIN")
        assertEquals(
            listOf("MOODS", "GENRES", "HI_RES_VAULT", "REDISCOVER", "DECADES", "LISTEN_AGAIN"),
            HomeSectionKeys.mergeNewSections(partial)
        )
    }

    @Test fun withoutAnchorTheyAreAppended() {
        assertEquals(
            listOf("GREETING", "GENRES", "DECADES", "HI_RES_VAULT", "REDISCOVER"),
            HomeSectionKeys.mergeNewSections(listOf("GREETING"))
        )
    }
}

class HomeSectionLogicTest {

    @Test fun decadeMath() {
        assertEquals(1990, decadeStart(1994))
        assertEquals(2000, decadeStart(2003))
        assertEquals(null, decadeStart(0))
        assertEquals(null, decadeStart(9999))
        assertEquals(null, decadeStart(1899))
        assertEquals("90s", decadeLabel(1990))
        assertEquals("60s", decadeLabel(1960))
        assertEquals("2000s", decadeLabel(2000))
        assertEquals("2010s", decadeLabel(2010))
    }

    @Test fun byDecade_groupsSortsAndDropsThinDecades() {
        val songs = listOf(
            T("a", year = 2015), T("b", year = 2012), T("c", year = 2019),
            T("d", year = 1994), T("e", year = 1991), T("f", year = 1999),
            T("g", year = 1975), T("h", year = 1976),          // only two 70s songs -> dropped
            T("i", year = 0), T("j", year = 0), T("k", year = 0) // no year -> ignored
        )
        val buckets = byDecade(songs, { it.year })
        assertEquals(listOf("90s", "2010s"), buckets.map { it.label })
        assertEquals(listOf(1990, 2010), buckets.map { it.startYear })
        assertEquals(listOf("d", "e", "f"), buckets[0].items.map { it.id })
        assertEquals(3, buckets[1].items.size)
    }

    @Test fun topGenres_mergesCaseKeepsCommonSpellingIgnoresUnknown() {
        val songs = listOf(
            T("1", genre = "Rock"), T("2", genre = "Rock"), T("3", genre = "rock"),
            T("4", genre = "Pop"), T("5", genre = "Pop"), T("6", genre = "Pop"), T("7", genre = "Pop"),
            T("8", genre = "Unknown"), T("9", genre = ""), T("10", genre = "<unknown>"),
            T("11", genre = "Jazz"), T("12", genre = "Jazz")   // only two -> dropped
        )
        val genres = topGenres(songs, { it.genre })
        assertEquals(listOf("Pop", "Rock"), genres.map { it.name })
        assertEquals(listOf(4, 3), genres.map { it.items.size })
    }

    @Test fun topGenres_multiGenreTagsCountForEach() {
        val songs = listOf(
            T("1", genre = "Pop; Rock"), T("2", genre = "Pop;Rock"), T("3", genre = "Rock; Pop"),
            T("4", genre = "Pop; pop")                              // same genre twice counts once
        )
        val genres = topGenres(songs, { it.genre }, minCount = 3)
        assertEquals(listOf("Pop", "Rock"), genres.map { it.name })
        assertEquals(4, genres[0].items.size)
        assertEquals(3, genres[1].items.size)
    }

    @Test fun topGenres_respectsLimitAndTieBreaksByName() {
        val songs = ('a'..'e').flatMap { c -> (1..3).map { T("$c$it", genre = c.toString()) } }
        val genres = topGenres(songs, { it.genre }, limit = 3)
        assertEquals(listOf("a", "b", "c"), genres.map { it.name })
    }

    @Test fun hiResVault_filtersAndRanksBestFirst() {
        val songs = listOf(
            T("cd", rate = 44100, bits = 16),
            T("b", title = "B", rate = 96000, bits = 24),
            T("a", title = "A", rate = 96000, bits = 24),
            T("top", rate = 192000, bits = 24),
            T("deep", rate = 96000, bits = 32)
        )
        val vault = hiResVault(songs, { it.bits >= 24 || it.rate >= 88200 }, { it.rate }, { it.bits }, { it.title })
        assertEquals(listOf("top", "deep", "a", "b"), vault.map { it.id })
        assertEquals(2, hiResVault(songs, { it.bits >= 24 }, { it.rate }, { it.bits }, { it.title }, limit = 2).size)
    }

    @Test fun hiResBadge_formats() {
        assertEquals("24/96", hiResBadge(24, 96000))
        assertEquals("24/88.2", hiResBadge(24, 88200))
        assertEquals("16/44.1", hiResBadge(16, 44100))
        assertEquals("48 kHz", hiResBadge(0, 48000))
        assertEquals("24-bit", hiResBadge(24, 0))
        assertEquals("", hiResBadge(0, 0))
    }

    @Test fun rediscover_skipsRecentIsStablePerDayAndChangesDaily() {
        val songs = (1..40).map { T("s$it") }
        val recent = setOf("s1", "s2", "s3")
        val today = rediscover(songs, { it.id }, recent, seed = 20_000L)
        assertEquals(16, today.size)
        assertTrue(today.none { it.id in recent })
        assertEquals(today.map { it.id }, rediscover(songs, { it.id }, recent, seed = 20_000L).map { it.id })
        // Library order must not matter for a given day
        assertEquals(today.map { it.id }, rediscover(songs.reversed(), { it.id }, recent, seed = 20_000L).map { it.id })
        assertFalse(today.map { it.id } == rediscover(songs, { it.id }, recent, seed = 20_001L).map { it.id })
    }

    @Test fun rediscover_withEverythingRecentIsEmpty() {
        val songs = (1..5).map { T("s$it") }
        assertEquals(0, rediscover(songs, { it.id }, songs.map { it.id }.toSet(), seed = 1L).size)
    }
}
