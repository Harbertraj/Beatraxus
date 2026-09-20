package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.data.SubtitleCache
import com.beatraxus.app.subtitles.data.SubtitleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SubtitleCacheTest {

    private lateinit var fakeContext: FakeTestContext
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "beatraxus_cache_test_" + System.currentTimeMillis()).apply { mkdirs() }
        cacheDir.deleteOnExit()
        fakeContext = object : FakeTestContext() {
            override fun getFilesDir(): File = cacheDir
        }
    }

    @Test
    fun testAtomicWriteAndRetrieveSubtitle() = runBlocking {
        val cache = SubtitleCache(fakeContext)
        val mediaKey = "test_media_key_123"

        val cachedSub = CachedSubtitle(
            subtitleId = "sub_001",
            language = "en",
            format = "SRT",
            releaseName = "Test.Release.mkv",
            localPath = "",
            source = SubtitleSource.ONLINE
        )

        val srtContent = "1\n00:00:01,000 --> 00:00:04,000\nHello Cache\n"
        val file = cache.saveSubtitle(mediaKey, cachedSub, srtContent)

        assertTrue(file.exists())
        assertEquals(srtContent, file.readText())

        val isCached = cache.isCached(mediaKey, "sub_001")
        assertTrue(isCached)

        val retrieved = cache.getCachedSubtitles(mediaKey)
        assertEquals(1, retrieved.size)
        assertEquals("sub_001", retrieved.first().subtitleId)
    }

    @Test
    fun testLruEvictionWhenSizeLimitExceeded() = runBlocking {
        // Set small limit of 5 KB
        val cache = SubtitleCache(fakeContext, maxCacheSizeBytes = 5 * 1024L)
        val mediaKey = "lru_media_key"

        // Create large content ~3 KB
        val largeContent = "1\n00:00:01,000 --> 00:00:04,000\n" + "A".repeat(3000)

        val sub1 = CachedSubtitle(subtitleId = "sub_lru_1", language = "en", localPath = "", lastUsed = 1000L)
        val sub2 = CachedSubtitle(subtitleId = "sub_lru_2", language = "es", localPath = "", lastUsed = 2000L)
        val sub3 = CachedSubtitle(subtitleId = "sub_lru_3", language = "fr", localPath = "", lastUsed = 3000L)

        cache.saveSubtitle(mediaKey, sub1, largeContent)
        cache.saveSubtitle(mediaKey, sub2, largeContent)
        cache.saveSubtitle(mediaKey, sub3, largeContent)

        // Oldest sub1 should have been evicted by LRU pruning
        val remaining = cache.getCachedSubtitles(mediaKey)
        assertFalse(remaining.any { it.subtitleId == "sub_lru_1" })
    }
}
