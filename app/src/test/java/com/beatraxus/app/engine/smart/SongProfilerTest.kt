package com.beatraxus.app.engine.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongProfilerTest {

    // ---------------------------------------------------------------- genre cleaning
    @Test fun id3NumbersBecomeNames() {
        assertEquals(listOf("Pop"), GenreTaxonomy.canonical("(13)"))
        assertEquals(listOf("Rock"), GenreTaxonomy.canonical("17"))
        assertEquals(listOf("Rock", "Pop"), GenreTaxonomy.canonical("(17)(13)"))
        assertEquals(listOf("Pop", "Funk"), GenreTaxonomy.canonical("62"))
    }

    @Test fun spellingsAndMultiValueTagsAreMerged() {
        assertEquals(listOf("Hip-Hop"), GenreTaxonomy.canonical("Hip Hop/Rap"))
        assertEquals(listOf("R&B", "Soul"), GenreTaxonomy.canonical("R&B/Soul"))
        assertEquals(listOf("Pop", "Rock"), GenreTaxonomy.canonical("Pop & Rock"))
        assertEquals(listOf("Pop", "Rock"), GenreTaxonomy.canonical("pop;rock"))
        assertEquals(listOf("Drum & Bass"), GenreTaxonomy.canonical("Drum & Bass"))
        assertEquals(listOf("Rock"), GenreTaxonomy.canonical("Rock & Roll"))
        assertEquals(listOf("Rock"), GenreTaxonomy.canonical("ROCK"))
        assertEquals(listOf("Lo-Fi"), GenreTaxonomy.canonical("lofi"))
        assertEquals(listOf("Hindi Film Music"), GenreTaxonomy.canonical("Bollywood"))
        assertEquals(listOf("Tamil Film Music"), GenreTaxonomy.canonical("kollywood"))
    }

    @Test fun unknownAndJunkAreDropped() {
        assertTrue(GenreTaxonomy.canonical("Unknown").isEmpty())
        assertTrue(GenreTaxonomy.canonical("<unknown>").isEmpty())
        assertTrue(GenreTaxonomy.canonical("Other").isEmpty())
        assertTrue(GenreTaxonomy.canonical("  ").isEmpty())
        assertTrue(GenreTaxonomy.canonical("www.site.com").isEmpty())
        assertTrue(GenreTaxonomy.canonical("2019").isEmpty())
        assertTrue(GenreTaxonomy.isUnknown(null))
        assertFalse(GenreTaxonomy.isUnknown("Jazz"))
    }

    @Test fun realButUnlistedGenresAreKept() {
        assertEquals(listOf("Gamelan"), GenreTaxonomy.canonical("gamelan"))
    }

    // ---------------------------------------------------------------- language
    @Test fun languageFromScriptArtistAndWords() {
        assertEquals("Tamil", LanguageSense.detect("காதல் பாடல்"))
        assertEquals("Hindi", LanguageSense.detect("तेरे बिना"))
        assertEquals("Korean", LanguageSense.detect("사랑해"))
        assertEquals("Tamil", LanguageSense.detect("Vaathi Coming", "Anirudh Ravichander"))
        assertEquals("Hindi", LanguageSense.detect("Tum Hi Ho", "Arijit Singh"))
        assertEquals(null, LanguageSense.detect("Shape of You", "Ed Sheeran"))
    }

    // ---------------------------------------------------------------- profiles
    private fun measured(
        title: String = "", artist: String = "", tag: String = "", year: Int = 0, lang: String? = null,
        bpm: Float, lufs: Float, dr: Float, bass: Float = 0.33f, mid: Float = 0.33f, treble: Float = 0.33f
    ) = SongProfiler.profile(
        ProfileInput(
            title = title, artist = artist, tagGenre = tag, year = year, language = lang, measured = true,
            tempoBpm = bpm, lufs = lufs, dynamicRange = dr, bass = bass, mid = mid, treble = treble
        )
    )

    @Test fun clubTrackIsPartyEnergyNotSleep() {
        val p = measured(title = "Night Rave", tag = "Electronic", bpm = 128f, lufs = -7f, dr = 5f, bass = 0.5f, mid = 0.3f, treble = 0.2f)
        assertTrue(p.moods.any { it in setOf("Party", "Energetic", "Workout") })
        assertTrue(p.moods.none { it in setOf("Sad", "Sleep", "Calm") })
    }

    @Test fun quietPianoIsCalmNeverWorkout() {
        val p = measured(title = "Soft Piano", tag = "Classical", bpm = 60f, lufs = -24f, dr = 16f, bass = 0.25f, mid = 0.55f, treble = 0.2f)
        assertTrue(p.moods.any { it in setOf("Calm", "Sleep", "Meditation", "Focus") })
        assertTrue(p.moods.none { it in setOf("Workout", "Aggressive", "Party") })
    }

    @Test fun slowSadPopGetsSadMoodsNotHappy() {
        val p = measured(title = "Broken Heart", tag = "Pop", bpm = 70f, lufs = -16f, dr = 12f, bass = 0.3f, mid = 0.5f, treble = 0.2f)
        assertTrue("Sad" in p.moods || "Heartbreak" in p.moods)
        assertFalse("Happy" in p.moods)
        assertFalse("Focus" in p.moods)
    }

    @Test fun contradictingMoodsNeverShareASong() {
        val p = measured(title = "x", tag = "Pop", bpm = 100f, lufs = -14f, dr = 10f)
        assertFalse("Workout" in p.moods && "Sleep" in p.moods)
        assertFalse("Happy" in p.moods && "Sad" in p.moods)
    }

    @Test fun metalIsAggressive() {
        val p = measured(title = "Rage", tag = "Heavy Metal", bpm = 160f, lufs = -8f, dr = 6f, bass = 0.3f, mid = 0.3f, treble = 0.4f)
        assertEquals(listOf("Metal"), p.genres)
        assertTrue("Aggressive" in p.moods)
    }

    @Test fun devotionalSongFoundByArtistAndTitleWords() {
        val p = measured(title = "Kandha Sashti Kavasam", artist = "Murugan Devotional", bpm = 80f, lufs = -15f, dr = 12f)
        assertTrue("Devotional" in p.genres)
        assertTrue("Devotional" in p.moods)
    }

    @Test fun regionalFilmSongWithoutTagStillLandsInItsFilmMusic() {
        val p = measured(title = "Kadhal Rojave", artist = "A R Rahman", tag = "Unknown", lang = "Tamil", bpm = 78f, lufs = -14f, dr = 11f, bass = 0.3f, mid = 0.45f, treble = 0.25f)
        assertTrue("Tamil Film Music" in p.genres)
        assertTrue(p.styles.any { it == "Tamil Melody" })
    }

    @Test fun massBeatsSongKeepsTagAndGetsFilmMusicAndStyle() {
        val p = measured(title = "Kuthu Mass", artist = "Anirudh", tag = "Pop", bpm = 135f, lufs = -8f, dr = 6f, bass = 0.45f, mid = 0.3f, treble = 0.25f)
        assertTrue("Pop" in p.genres)
        assertTrue("Tamil Film Music" in p.genres)
        assertTrue(p.styles.any { it.contains("Kuthu") || it.contains("Mass Beats") })
    }

    @Test fun realTagBeatsWeakAiGuess() {
        val p = SongProfiler.profile(
            ProfileInput(title = "Some Song", tagGenre = "Jazz", aiGenre = "Hip-Hop", aiGenreConfidence = 0f,
                measured = true, tempoBpm = 90f, lufs = -14f, dynamicRange = 12f)
        )
        assertEquals(listOf("Jazz"), p.genres)
    }

    @Test fun songWithNoInformationGetsUnknownAndNoInventedMood() {
        val p = SongProfiler.profile(ProfileInput(title = "Untitled", tagGenre = "Other"))
        assertEquals(listOf("Unknown"), p.genres)
        assertTrue(p.moods.isEmpty())
    }

    @Test fun oldSongsGetNostalgia() {
        val p = measured(title = "Evergreen", tag = "Pop", year = 1982, bpm = 100f, lufs = -15f, dr = 12f)
        assertTrue("Retro Classics" in p.styles || "Nostalgia" in p.moods)
    }

    // ---------------------------------------------------------------- grouping
    private class S(val id: String, val p: SongProfile)

    @Test fun groupingMergesEveryGroupAndPutsUnknownLast() {
        val tamil = measured(title = "Kadhal Rojave", tag = "Unknown", lang = "Tamil", bpm = 78f, lufs = -14f, dr = 11f, bass = 0.3f, mid = 0.45f, treble = 0.25f)
        val edm = measured(title = "Night Rave", tag = "Electronic", bpm = 128f, lufs = -7f, dr = 5f, bass = 0.5f, mid = 0.3f, treble = 0.2f)
        val nothing = SongProfiler.profile(ProfileInput(title = "Untitled", tagGenre = "Other"))
        val songs = (1..4).map { S("e$it", edm) } + (1..4).map { S("t$it", tamil) } + S("n", nothing)

        val groups = LibraryGrouping.byLabels(songs, { it.p })
        val genres = groups.filter { !it.isStyle }
        assertEquals("Unknown", genres.last().label)
        assertEquals(4, genres.first { it.label == "Electronic" }.items.size)
        assertTrue(groups.any { it.isStyle && it.items.size >= 3 })
        assertTrue(groups.filter { it.isStyle }.all { LibraryGrouping.isStyleLabel(it.label) })

        val top = LibraryGrouping.topGroups(songs, { it.p })
        assertTrue(top.none { it.label == "Unknown" })
    }

    @Test fun smallStylesAreHidden() {
        val edm = measured(title = "Night Rave", tag = "Electronic", bpm = 128f, lufs = -7f, dr = 5f, bass = 0.5f, mid = 0.3f, treble = 0.2f)
        val groups = LibraryGrouping.byLabels(listOf(S("a", edm), S("b", edm)), { it.p })
        assertTrue(groups.none { it.isStyle })
    }
}
