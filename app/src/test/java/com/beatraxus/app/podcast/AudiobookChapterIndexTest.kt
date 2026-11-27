package com.beatraxus.app.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudiobookChapterIndexTest {
    private val chapters = listOf(
        AudiobookChapter(0, "Opening", 0L, 60_000L),
        AudiobookChapter(1, "Chapter 1", 60_000L, 180_000L),
        AudiobookChapter(2, "Chapter 2", 180_000L, null)
    )

    @Test
    fun findsCurrentChapterAtBoundaries() {
        assertEquals(0, AudiobookChapterIndex.chapterAt(chapters, 59_999L))
        assertEquals(1, AudiobookChapterIndex.chapterAt(chapters, 60_000L))
        assertEquals(2, AudiobookChapterIndex.chapterAt(chapters, 240_000L))
    }

    @Test
    fun clampsProgressFraction() {
        assertEquals(0f, AudiobookChapterIndex.progressFraction(-1L, 100L))
        assertEquals(0.5f, AudiobookChapterIndex.progressFraction(50L, 100L))
        assertEquals(1f, AudiobookChapterIndex.progressFraction(150L, 100L))
        assertEquals(0f, AudiobookChapterIndex.progressFraction(10L, 0L))
    }

    @Test
    fun rejectsNonMonotonicChapters() {
        val invalid = listOf(
            AudiobookChapter(0, "One", 0L, 100L),
            AudiobookChapter(1, "Two", 0L, null)
        )
        assertThrows(IllegalArgumentException::class.java) { AudiobookChapterIndex.validate(invalid) }
    }
}
