package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTranslationLogicTest {

    @Test
    fun `batches never exceed the limit and keep every line in order`() {
        val texts = List(50) { "line number $it with some words" }
        val batches = LyricsTranslationLogic.batches(texts, maxChars = 200)
        assertEquals(texts.indices.toList(), batches.flatten())
        batches.forEach { b ->
            val size = b.sumOf { texts[it].length } + 8 * (b.size - 1)
            assertTrue("batch too big: $size", size <= 200)
        }
        assertTrue(batches.size > 1)
    }

    @Test
    fun `one oversized line still gets its own batch`() {
        val batches = LyricsTranslationLogic.batches(listOf("a", "x".repeat(500), "b"), maxChars = 100)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), batches)
    }

    @Test
    fun `empty input gives no batches`() {
        assertTrue(LyricsTranslationLogic.batches(emptyList()).isEmpty())
    }

    @Test
    fun `payload and split round trip`() {
        val texts = listOf("one", "two", "three")
        val payload = LyricsTranslationLogic.payload(texts, listOf(0, 1, 2))
        // a translator keeps the markers and changes the words
        val translated = payload.replace("one", "uno").replace("two", "dos").replace("three", "tres")
        assertEquals(listOf("uno", "dos", "tres"), LyricsTranslationLogic.split(translated, 3))
    }

    @Test
    fun `split rejects an answer with the wrong number of lines`() {
        val payload = LyricsTranslationLogic.payload(listOf("a", "b"), listOf(0, 1))
        assertNull(LyricsTranslationLogic.split(payload, 3))
    }

    @Test
    fun `split rejects blank parts`() {
        assertNull(LyricsTranslationLogic.split("x\n" + LyricsTranslationLogic.marker(1) + "\n ", 2))
    }

    @Test
    fun `only sung text is translatable`() {
        assertTrue(LyricsTranslationLogic.translatable("Hello world"))
        assertTrue(LyricsTranslationLogic.translatable("மரண மாஸ்"))
        assertFalse(LyricsTranslationLogic.translatable(""))
        assertFalse(LyricsTranslationLogic.translatable("   "))
        assertFalse(LyricsTranslationLogic.translatable("[Chorus]"))
        assertFalse(LyricsTranslationLogic.translatable("♪ ♪"))
        assertFalse(LyricsTranslationLogic.translatable("..."))
    }

    @Test
    fun `cache key changes with target and text`() {
        val a = LyricsTranslationLogic.cacheKey("s1", "en", listOf("x", "y"))
        assertEquals(a, LyricsTranslationLogic.cacheKey("s1", "en", listOf("x", "y")))
        assertNotEquals(a, LyricsTranslationLogic.cacheKey("s1", "ta", listOf("x", "y")))
        assertNotEquals(a, LyricsTranslationLogic.cacheKey("s1", "en", listOf("x", "z")))
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `same language ignores region but keeps chinese scripts apart`() {
        assertTrue(LyricsTranslationLogic.sameLanguage("en", "en"))
        assertTrue(LyricsTranslationLogic.sameLanguage("en-GB", "en"))
        assertFalse(LyricsTranslationLogic.sameLanguage("hi", "en"))
        assertFalse(LyricsTranslationLogic.sameLanguage("", "en"))
        assertTrue(LyricsTranslationLogic.sameLanguage("zh-CN", "zh-CN"))
        assertFalse(LyricsTranslationLogic.sameLanguage("zh-TW", "zh-CN"))
    }

    @Test
    fun `language list has unique tags`() {
        val tags = LyricsTranslationLogic.LANGUAGES.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }
}
