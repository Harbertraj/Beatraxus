package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSortTest {

    private data class Item(
        val name: String,
        val date: Long = 1L,
        val size: Long = 1L,
        val duration: Long = 1L
    )

    private fun sort(items: List<Item>, type: SortType, ascending: Boolean) = VideoSort.sort(
        items = items,
        type = type,
        ascending = ascending,
        name = { it.name },
        date = { it.date },
        size = { it.size },
        duration = { it.duration }
    ).map { it.name }

    @Test
    fun `name sort ignores case`() {
        val items = listOf(Item("banana"), Item("Apple"), Item("cherry"))
        assertEquals(listOf("Apple", "banana", "cherry"), sort(items, SortType.NAME, true))
        assertEquals(listOf("cherry", "banana", "Apple"), sort(items, SortType.NAME, false))
    }

    @Test
    fun `name sort reads numbers as numbers`() {
        val items = listOf(Item("Episode 10"), Item("Episode 2"), Item("Episode 1"))
        assertEquals(listOf("Episode 1", "Episode 2", "Episode 10"), sort(items, SortType.NAME, true))
        assertEquals(listOf("Episode 10", "Episode 2", "Episode 1"), sort(items, SortType.NAME, false))
    }

    @Test
    fun `leading zeros and very long numbers do not break the comparison`() {
        assertTrue(VideoSort.naturalCompare("clip007", "clip10") < 0)
        assertEquals(0, VideoSort.naturalCompare("clip01", "clip1"))
        assertTrue(VideoSort.naturalCompare("n99999999999999999999999", "n100000000000000000000000") < 0)
    }

    @Test
    fun `natural compare handles empty text and prefixes`() {
        assertEquals(0, VideoSort.naturalCompare("", ""))
        assertTrue(VideoSort.naturalCompare("", "a") < 0)
        assertTrue(VideoSort.naturalCompare("abc", "ab") > 0)
        assertTrue(VideoSort.naturalCompare("ab", "abc") < 0)
    }

    @Test
    fun `date sort goes oldest to newest and back`() {
        val items = listOf(Item("b", date = 200), Item("a", date = 300), Item("c", date = 100))
        assertEquals(listOf("c", "b", "a"), sort(items, SortType.DATE_ADDED, true))
        assertEquals(listOf("a", "b", "c"), sort(items, SortType.DATE_ADDED, false))
    }

    @Test
    fun `size sort goes smallest to largest and back`() {
        val items = listOf(Item("mid", size = 500), Item("big", size = 9_000), Item("small", size = 10))
        assertEquals(listOf("small", "mid", "big"), sort(items, SortType.FILE_SIZE, true))
        assertEquals(listOf("big", "mid", "small"), sort(items, SortType.FILE_SIZE, false))
    }

    @Test
    fun `duration sort goes shortest to longest and back`() {
        val items = listOf(Item("long", duration = 7_200_000), Item("short", duration = 30_000), Item("mid", duration = 600_000))
        assertEquals(listOf("short", "mid", "long"), sort(items, SortType.DURATION, true))
        assertEquals(listOf("long", "mid", "short"), sort(items, SortType.DURATION, false))
    }

    @Test
    fun `unknown values are listed last in both directions`() {
        val items = listOf(
            Item("unknown-b", duration = 0),
            Item("two", duration = 2_000),
            Item("unknown-a", duration = 0),
            Item("one", duration = 1_000)
        )
        assertEquals(listOf("one", "two", "unknown-a", "unknown-b"), sort(items, SortType.DURATION, true))
        assertEquals(listOf("two", "one", "unknown-a", "unknown-b"), sort(items, SortType.DURATION, false))
    }

    @Test
    fun `ties fall back to name order even when sorting descending`() {
        val items = listOf(Item("c", size = 5), Item("a", size = 5), Item("b", size = 5), Item("z", size = 9))
        assertEquals(listOf("a", "b", "c", "z"), sort(items, SortType.FILE_SIZE, true))
        assertEquals(listOf("z", "a", "b", "c"), sort(items, SortType.FILE_SIZE, false))
    }

    @Test
    fun `sorting keeps every item and leaves the input alone`() {
        val items = listOf(Item("b", size = 2), Item("a", size = 1), Item("c", size = 3))
        val before = items.toList()
        SortType.values().forEach { type ->
            assertEquals(items.size, sort(items, type, true).size)
            assertEquals(items.size, sort(items, type, false).size)
        }
        assertEquals(before, items)
    }

    @Test
    fun `an empty list stays empty`() {
        SortType.values().forEach { type ->
            assertTrue(sort(emptyList(), type, true).isEmpty())
        }
    }
}
