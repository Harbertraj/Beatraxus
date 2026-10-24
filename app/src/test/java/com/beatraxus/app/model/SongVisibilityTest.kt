package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongVisibilityTest {

    private data class Item(val id: String, val durationMs: Long)

    private fun visible(items: List<Item>, hidden: Set<String> = emptySet(), minSec: Int = 0) =
        SongVisibility.visible(items, hidden, minSec, { it.id }, { it.durationMs }).map { it.id }

    private val library = listOf(
        Item("ringtone", 8_000),
        Item("jingle", 29_000),
        Item("clip", 30_000),
        Item("song", 200_000),
        Item("cloud-unread", 0)
    )

    @Test
    fun `with nothing hidden and no minimum the library is untouched`() {
        assertEquals(library, SongVisibility.visible(library, emptySet(), 0, { it.id }, { it.durationMs }))
        assertEquals(listOf("ringtone", "jingle", "clip", "song", "cloud-unread"), visible(library))
    }

    @Test
    fun `hidden songs are left out and the order of the rest is kept`() {
        assertEquals(listOf("ringtone", "clip", "cloud-unread"), visible(library, hidden = setOf("jingle", "song")))
    }

    @Test
    fun `a hidden id that is not in the library does nothing`() {
        assertEquals(5, visible(library, hidden = setOf("deleted-long-ago")).size)
    }

    @Test
    fun `songs shorter than the minimum are left out`() {
        assertEquals(listOf("clip", "song", "cloud-unread"), visible(library, minSec = 30))
        assertEquals(listOf("song", "cloud-unread"), visible(library, minSec = 60))
    }

    @Test
    fun `a song exactly as long as the minimum stays`() {
        assertEquals(listOf("clip", "song", "cloud-unread"), visible(library, minSec = 30))
        assertFalse(SongVisibility.isTooShort(30_000, 30))
        assertTrue(SongVisibility.isTooShort(29_999, 30))
    }

    @Test
    fun `a song with unknown duration is never left out by the minimum`() {
        assertFalse(SongVisibility.isTooShort(0, 120))
        assertTrue("cloud-unread" in visible(library, minSec = 120))
    }

    @Test
    fun `hiding and the minimum work together`() {
        assertEquals(listOf("cloud-unread"), visible(library, hidden = setOf("song"), minSec = 60))
    }

    @Test
    fun `a stored value that is out of range is brought back`() {
        assertEquals(0, SongVisibility.normalizeMinDurationSec(-5))
        assertEquals(600, SongVisibility.normalizeMinDurationSec(100_000))
        assertEquals(45, SongVisibility.normalizeMinDurationSec(45))
        assertEquals(0, SongVisibility.MIN_DURATION_CHOICES_SEC.first())
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("Off", SongVisibility.label(0))
        assertEquals("15 sec", SongVisibility.label(15))
        assertEquals("1 min", SongVisibility.label(60))
        assertEquals("2 min", SongVisibility.label(120))
        assertEquals("1 min 30 sec", SongVisibility.label(90))
    }

    @Test
    fun `an empty library stays empty`() {
        assertTrue(visible(emptyList(), hidden = setOf("a"), minSec = 30).isEmpty())
    }
}
