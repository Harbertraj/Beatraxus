package com.beatraxus.app.engine

import com.beatraxus.app.model.StemQuality
import com.beatraxus.app.model.StemSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StemCacheStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = StemCacheStore(File(tmp.root, "stems"))

    private fun fill(store: StemCacheStore, song: String, q: StemQuality, bytes: Int = 8000, mark: Boolean = true) {
        store.dirFor(song, q, create = true)
        store.stemFiles(song, q).forEach { it.writeBytes(ByteArray(bytes)) }
        if (mark) store.markDone(song, q)
    }

    // ── marker ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun markerRecordsVersionEngineAndStemCount() {
        assertEquals("3:htdemucs6s:6", StemCacheFormat.marker(StemQuality.MAX))
        assertEquals("3:scnet-int8:4", StemCacheFormat.marker(StemQuality.FAST))
        val m = StemCacheFormat.parse("3:htdemucs6s:6\n")!!
        assertEquals(StemCacheFormat.Marker("3", "htdemucs6s", 6), m)
    }

    @Test
    fun markerParsingRejectsGarbageAndOldFormats() {
        for (bad in listOf(null, "", "2", "3:htdemucs6s", "3::6", ":x:6", "3:x:six", "3:x:6:extra", " ")) {
            assertNull("'$bad'", StemCacheFormat.parse(bad))
        }
    }

    @Test
    fun markerMustMatchTheRequestedEngine() {
        assertTrue(StemCacheFormat.matches("3:htdemucs6s:6", StemQuality.MAX))
        assertFalse(StemCacheFormat.matches("3:htdemucs6s:6", StemQuality.FAST))
        assertFalse(StemCacheFormat.matches("3:scnet-int8:4", StemQuality.MAX))
        assertFalse(StemCacheFormat.matches("3:htdemucs6s:4", StemQuality.MAX))   // wrong stem count
        assertFalse(StemCacheFormat.matches("2:htdemucs6s:6", StemQuality.MAX))   // old cache version
        assertFalse(StemCacheFormat.matches("2", StemQuality.FAST))               // the old bare "2" marker
    }

    // ── cached or not ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun cachedOnlyForTheEngineThatWroteIt() {
        val s = store()
        fill(s, "song1", StemQuality.FAST)
        assertTrue(s.isCached("song1", StemQuality.FAST))
        assertFalse(s.isCached("song1", StemQuality.MAX))
        assertEquals(4, s.stemFiles("song1", StemQuality.FAST).size)
        assertEquals(6, s.stemFiles("song1", StemQuality.MAX).size)
    }

    @Test
    fun aFastMarkerInTheMaxFolderIsNotACacheHit() {
        val s = store()
        fill(s, "song1", StemQuality.MAX, mark = false)
        File(s.dirFor("song1", StemQuality.MAX), StemCacheFormat.DONE_FILENAME).writeText(StemCacheFormat.marker(StemQuality.FAST))
        assertFalse(s.isCached("song1", StemQuality.MAX))
    }

    @Test
    fun missingEmptyOrUnevenStemFilesAreNotACacheHit() {
        val s = store()
        fill(s, "a", StemQuality.MAX)
        assertTrue(s.isCached("a", StemQuality.MAX))
        s.stemFiles("a", StemQuality.MAX)[3].delete()
        assertFalse(s.isCached("a", StemQuality.MAX))

        fill(s, "b", StemQuality.MAX)
        s.stemFiles("b", StemQuality.MAX)[4].writeBytes(ByteArray(10))    // cut short: a crashed write
        assertFalse(s.isCached("b", StemQuality.MAX))

        fill(s, "c", StemQuality.MAX, bytes = 0)
        assertFalse(s.isCached("c", StemQuality.MAX))
    }

    @Test
    fun fastAndMaxLiveInSeparateFoldersAndDeletingOneKeepsTheOther() {
        val s = store()
        fill(s, "song", StemQuality.FAST)
        fill(s, "song", StemQuality.MAX)
        assertNotEquals(s.dirFor("song", StemQuality.FAST), s.dirFor("song", StemQuality.MAX))
        s.deleteStems("song", StemQuality.FAST)
        assertFalse(s.isCached("song", StemQuality.FAST))
        assertTrue(s.isCached("song", StemQuality.MAX))
        s.deleteStems("song")
        assertFalse(s.isCached("song", StemQuality.MAX))
    }

    @Test
    fun stemFilesAreNamedByStem() {
        val names = store().stemFiles("x", StemQuality.MAX).map { it.name }
        assertEquals(listOf("vocals.pcm", "drums.pcm", "bass.pcm", "guitar.pcm", "piano.pcm", "other.pcm"), names)
        assertEquals(
            listOf("vocals.pcm", "drums.pcm", "bass.pcm", "other.pcm"),
            store().stemFiles("x", StemQuality.FAST).map { it.name }
        )
    }

    // ── purge ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun purgeRemovesOldLayoutOldMarkersAndMismatchesButKeepsGoodCaches() {
        val s = store()
        val root = s.root
        // Version 2 layout: stems/<song>/ with a bare "2" marker and four files.
        File(root, "oldsong").apply { mkdirs(); File(this, "done").writeText("2"); File(this, "vocals.pcm").writeBytes(ByteArray(8)) }
        // Version 3 folders with the wrong marker.
        fill(s, "badmarker", StemQuality.MAX, mark = false)
        File(s.dirFor("badmarker", StemQuality.MAX), "done").writeText("3:scnet-int8:4")
        fill(s, "oldver", StemQuality.FAST, mark = false)
        File(s.dirFor("oldver", StemQuality.FAST), "done").writeText("2:scnet-int8:4")
        // Unknown quality folder.
        File(root, "v3/weird/song").mkdirs()
        // Good ones.
        fill(s, "goodfast", StemQuality.FAST)
        fill(s, "goodmax", StemQuality.MAX)

        val removed = s.purgeOutdated()
        assertEquals(4, removed)
        assertFalse(File(root, "oldsong").exists())
        assertFalse(s.dirFor("badmarker", StemQuality.MAX).exists())
        assertFalse(s.dirFor("oldver", StemQuality.FAST).exists())
        assertFalse(File(root, "v3/weird").exists())
        assertTrue(s.isCached("goodfast", StemQuality.FAST))
        assertTrue(s.isCached("goodmax", StemQuality.MAX))
    }

    @Test
    fun purgeRemovesOldUnfinishedFoldersButNotFreshOnes() {
        val s = store()
        fill(s, "fresh", StemQuality.MAX, mark = false)
        fill(s, "stale", StemQuality.MAX, mark = false)
        s.dirFor("stale", StemQuality.MAX).setLastModified(System.currentTimeMillis() - 3 * 3600_000L)
        s.purgeOutdated()
        assertTrue(s.dirFor("fresh", StemQuality.MAX).exists())
        assertFalse(s.dirFor("stale", StemQuality.MAX).exists())
    }

    // ── size guard and clear ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun sizeGuardDeletesLeastRecentlyUsedFirstAndKeepsTheSongBeingWritten() {
        val s = store()
        val now = System.currentTimeMillis()
        fill(s, "oldest", StemQuality.FAST, bytes = 1000)
        fill(s, "middle", StemQuality.FAST, bytes = 1000)
        fill(s, "newest", StemQuality.FAST, bytes = 1000)
        fill(s, "writing", StemQuality.FAST, bytes = 1000)
        File(s.dirFor("oldest", StemQuality.FAST), "done").setLastModified(now - 3000)
        File(s.dirFor("middle", StemQuality.FAST), "done").setLastModified(now - 2000)
        File(s.dirFor("newest", StemQuality.FAST), "done").setLastModified(now - 1000)
        File(s.dirFor("writing", StemQuality.FAST), "done").setLastModified(now - 9000)   // the oldest, but protected
        val one = s.totalSizeBytes() / 4

        // Room for three songs plus the incoming one: exactly one must go, the oldest that is not protected.
        val removed = s.enforceLimit(limitBytes = one * 4, incomingBytes = one, keepSongId = "writing", keepQuality = StemQuality.FAST)
        assertEquals(1, removed)
        assertFalse(s.isCached("oldest", StemQuality.FAST))
        assertTrue(s.isCached("middle", StemQuality.FAST))
        assertTrue(s.isCached("newest", StemQuality.FAST))
        assertTrue(s.dirFor("writing", StemQuality.FAST).exists())
    }

    @Test
    fun sizeGuardDoesNothingWhenThereIsRoom() {
        val s = store()
        fill(s, "a", StemQuality.FAST)
        assertEquals(0, s.enforceLimit(Long.MAX_VALUE / 2, 1000))
        assertTrue(s.isCached("a", StemQuality.FAST))
    }

    @Test
    fun clearAllRemovesEverythingIncludingOldLayouts() {
        val s = store()
        fill(s, "a", StemQuality.FAST)
        fill(s, "a", StemQuality.MAX)
        File(s.root, "legacy").mkdirs(); File(s.root, "legacy/x").writeBytes(ByteArray(10))
        assertTrue(s.totalSizeBytes() > 0)
        s.clearAll()
        assertEquals(0L, s.totalSizeBytes())
        assertFalse(s.isCached("a", StemQuality.FAST))
    }

    // ── reader ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun readerReadsEveryStemOfTheEngineAndZeroFillsPastTheEnd() {
        val s = store()
        val frames = 100
        for (q in StemQuality.entries) {
            s.dirFor("r", q, create = true)
            val specs = StemSpecs.forQuality(q)
            specs.forEachIndexed { i, spec ->
                val bb = ByteBuffer.allocate(frames * 8).order(ByteOrder.nativeOrder())
                for (f in 0 until frames) { bb.putFloat((i + 1).toFloat()); bb.putFloat(-(i + 1).toFloat() - f) }
                File(s.dirFor("r", q), spec.fileName).writeBytes(bb.array())
            }
            s.markDone("r", q)
            assertTrue(s.isCached("r", q))
            s.openReader("r", q).use { r ->
                assertEquals(specs.size, r.stemCount)
                val out = FloatArray(40)
                for (i in specs.indices) {
                    assertTrue(r.read(i, 90, 20, out))
                    assertEquals((i + 1).toFloat(), out[0], 0f)
                    assertEquals(-(i + 1).toFloat() - 90, out[1], 0f)
                    assertEquals(0f, out[20], 0f)      // frames 100.. are past the end: zero filled
                    assertEquals(0f, out[39], 0f)
                }
                assertFalse(r.read(specs.size, 0, 10, out))       // no such stem
                assertFalse(r.read(0, 100, 10, out))              // wholly past the end
            }
        }
    }
}
