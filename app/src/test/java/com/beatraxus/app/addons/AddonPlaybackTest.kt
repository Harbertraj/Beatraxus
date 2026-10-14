package com.beatraxus.app.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hand-off to the app's single player: what gets queued, with which metadata. (The final call into
 * PlayerViewModel needs Android and is covered by the on-device check in the PR notes.)
 */
class AddonPlaybackTest {

    private fun audio(id: String, url: String = "https://s.example/$id", artist: String? = "Artist", art: String? = "https://i.example/$id") =
        AddonMediaItem(
            id = id, title = "Song $id", subtitle = "Artist · Album", artworkUrl = art, mediaType = "audio",
            durationMs = 125_000L, directPlayUrl = url, artist = artist, album = "Album", trackNumber = 3
        )

    // ---- metadata handed to the player -----------------------------------------------------------------

    @Test fun songSpec_usesRichMetadataWhenAvailable() {
        val spec = songSpec("preset_jamendo", "Jamendo Music", audio("1"), "https://fresh.example/1")
        assertEquals("preset_jamendo_1", spec.id)
        assertEquals("Song 1", spec.title)
        assertEquals("Artist", spec.artist)
        assertEquals("Album", spec.album)
        assertEquals("https://fresh.example/1", spec.url)
        assertEquals("https://i.example/1", spec.artworkUrl)
        assertEquals(125_000L, spec.durationMs)
        assertEquals(3, spec.trackNumber)
    }

    @Test fun songSpec_keepsOldBehaviourForAddonsWithoutArtistAndAlbum() {
        // Subsonic/Emby-style items only fill title + subtitle.
        val legacy = AddonMediaItem("9", "T", "Artist X · Album Y", null, "audio", 0L, "")
        val spec = songSpec("sub", "Navidrome", legacy, "https://u")
        assertEquals("Artist X · Album Y", spec.artist)
        assertEquals("Navidrome", spec.album)
        assertNull(spec.artworkUrl)
        val bare = AddonMediaItem("9", "T", null, "", "audio", 0L, "")
        assertEquals("Navidrome", songSpec("sub", "Navidrome", bare, "u").artist)
        assertNull(songSpec("sub", "Navidrome", bare, "u").artworkUrl)
    }

    // ---- queue planning --------------------------------------------------------------------------------

    @Test fun planQueue_playsTheTappedTrackInsideItsList() {
        val list = listOf(audio("a"), audio("b"), audio("c"))
        val plan = planQueue(list[1], "https://fresh.example/b", list)
        assertEquals(listOf("a", "b", "c"), plan.items.map { it.id })
        assertEquals(1, plan.startIndex)
        assertEquals("https://fresh.example/b", plan.items[1].directPlayUrl) // the freshly resolved URL
        assertEquals("https://s.example/a", plan.items[0].directPlayUrl)
    }

    @Test fun planQueue_singleItemWhenNeighboursCannotPlayDirectly() {
        // Subsonic-style list: URLs are only resolved on tap, so only that track can be queued.
        val list = listOf(audio("a", url = ""), audio("b", url = ""), audio("c", url = ""))
        val plan = planQueue(list[1], "https://resolved/b", list)
        assertEquals(1, plan.items.size)
        assertEquals(0, plan.startIndex)
        assertEquals("https://resolved/b", plan.items[0].directPlayUrl)
    }

    @Test fun planQueue_ignoresVideoAndUnknownItems() {
        val video = audio("v").copy(mediaType = "video")
        val list = listOf(audio("a"), video, audio("b"))
        assertEquals(listOf("a", "b"), planQueue(list[2], "u", list).items.map { it.id })
        assertEquals(1, planQueue(list[2], "u", list).startIndex)
        // Tapped item not part of the list (e.g. opened from search): just that item.
        assertEquals(listOf("zzz"), planQueue(audio("zzz"), "u", list).items.map { it.id })
        // A list of one is just that item.
        assertEquals(1, planQueue(audio("a"), "u", listOf(audio("a"))).items.size)
        assertEquals(1, planQueue(audio("a"), "u", emptyList()).items.size)
    }

    // ---- audio vs video --------------------------------------------------------------------------------

    @Test fun archiveItemsAreTypedByTheFileThatWasPicked() {
        val movie = audio("m").copy(mediaType = "video")
        assertFalse(isVideoStream(true, movie, "https://archive.org/download/m/talk.mp3"))
        assertTrue(isVideoStream(true, audio("a"), "https://archive.org/download/a/film.mp4"))
        assertTrue(isVideoStream(true, movie, "https://archive.org/download/m/noext")) // unknown: trust the item
        assertFalse(isVideoStream(true, audio("a"), "https://archive.org/download/a/x.mp3?cb=1.mp4"))
    }

    @Test fun otherAddonsTrustTheItemType() {
        assertTrue(isVideoStream(false, audio("a").copy(mediaType = "video"), "https://x/clip.mp3"))
        assertFalse(isVideoStream(false, audio("a"), "https://x/clip.mp4"))
    }

    // ---- display helpers -------------------------------------------------------------------------------

    @Test fun durationLabel() {
        assertEquals("2:05", audio("a").durationLabel)
        assertEquals("1:02:03", audio("a").copy(durationMs = 3_723_000L).durationLabel)
        assertEquals("0:09", audio("a").copy(durationMs = 9_000L).durationLabel)
        assertNull(audio("a").copy(durationMs = 0L).durationLabel)
    }

    // ---- registration ----------------------------------------------------------------------------------
    // Read straight from the Kotlin sources (same approach as addons/build_zips.py) so the three places a
    // protocol must be registered can't drift apart.

    private fun source(name: String): String {
        val roots = listOf("src/main/java", "app/src/main/java")
        val file = roots.map { File("$it/com/beatraxus/app/addons/$name") }.first { it.exists() }
        return file.readText()
    }

    @Test fun jamendoIsRegisteredAsItsOwnProvider() {
        val installer = source("AddonInstaller.kt")
        val valid = Regex("""validProtocols = listOf\((.*?)\)""").find(installer)!!.groupValues[1]
        assertTrue(valid.contains("\"jamendo\""))
        assertTrue(valid.contains("\"archive_org\"")) // Internet Archive is still registered, separately

        val manager = source("AddonManager.kt")
        assertTrue(manager.contains("\"jamendo\" -> JamendoAddon(id, displayName, authType)"))
        assertTrue(manager.contains("\"archive_org\" -> ArchiveOrgAddon(id, displayName, authType)"))

        val presets = source("AddonPresets.kt")
        assertTrue(presets.contains("id = \"preset_jamendo\""))
        assertTrue(presets.contains("displayName = \"Jamendo Music\""))
        assertTrue(presets.contains("protocol = \"jamendo\""))
        assertTrue(presets.contains("authType = \"api_key\""))
        assertTrue(presets.contains("id = \"preset_archive\"")) // untouched
    }
}
