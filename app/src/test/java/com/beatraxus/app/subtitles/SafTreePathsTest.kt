package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.SafTreePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafTreePathsTest {

    @Test
    fun primaryAliasesAreNormalised() {
        assertEquals("/storage/emulated/0/Movies", SafTreePaths.normalizeStoragePath("/sdcard/Movies"))
        assertEquals("/storage/emulated/0/Movies", SafTreePaths.normalizeStoragePath("/storage/self/primary/Movies/"))
        assertEquals("/storage/emulated/0", SafTreePaths.normalizeStoragePath("/sdcard"))
        assertEquals("/storage/emulated/0/Movies", SafTreePaths.normalizeStoragePath("/storage/emulated/0/Movies"))
        assertEquals("/storage/1A2B-3C4D/Films", SafTreePaths.normalizeStoragePath("/storage/1A2B-3C4D/Films"))
        // A name that only starts like an alias is left alone.
        assertEquals("/sdcard2/Movies", SafTreePaths.normalizeStoragePath("/sdcard2/Movies"))
    }

    @Test
    fun docIdToPath() {
        assertEquals("/storage/emulated/0/Movies/Show", SafTreePaths.docIdToPath("primary:Movies/Show"))
        assertEquals("/storage/emulated/0", SafTreePaths.docIdToPath("primary:"))
        assertEquals("/storage/1A2B-3C4D/Films", SafTreePaths.docIdToPath("1A2B-3C4D:Films"))
        assertNull(SafTreePaths.docIdToPath("raw:/data/x"))
        assertNull(SafTreePaths.docIdToPath("nocolon"))
        assertNull(SafTreePaths.docIdToPath(":x"))
    }

    @Test
    fun folderInsideAGrantGetsItsOwnDocId() {
        assertEquals("primary:Movies/Show/S1", SafTreePaths.folderDocId("/storage/emulated/0/Movies/Show/S1", "primary:Movies"))
        assertEquals("primary:Movies", SafTreePaths.folderDocId("/sdcard/Movies", "primary:Movies"))
        assertEquals("1A2B-3C4D:Films/Old", SafTreePaths.folderDocId("/storage/1A2B-3C4D/Films/Old", "1A2B-3C4D:Films"))
    }

    @Test
    fun folderOutsideAGrantIsRejected() {
        assertNull(SafTreePaths.folderDocId("/storage/emulated/0/Music", "primary:Movies"))
        // "Movies2" must not count as inside "Movies".
        assertNull(SafTreePaths.folderDocId("/storage/emulated/0/Movies2", "primary:Movies"))
        // Right folder name, wrong volume.
        assertNull(SafTreePaths.folderDocId("/storage/1A2B-3C4D/Movies", "primary:Movies"))
        assertNull(SafTreePaths.folderDocId("/storage/emulated/0/Movies", "raw:/x"))
    }

    @Test
    fun wholeVolumeGrantCoversEverythingOnIt() {
        assertEquals("primary:Movies/Show", SafTreePaths.folderDocId("/storage/emulated/0/Movies/Show", "primary:"))
    }

    @Test
    fun deepestCoveringGrantIsChosen() {
        val grants = listOf("primary:", "primary:Movies", "primary:Movies/Show", "primary:Music")
        assertEquals("primary:Movies/Show", SafTreePaths.bestTree("/storage/emulated/0/Movies/Show/S1", grants))
        assertEquals("primary:Movies", SafTreePaths.bestTree("/storage/emulated/0/Movies/Other", grants))
        assertEquals("primary:", SafTreePaths.bestTree("/storage/emulated/0/Download", grants))
        assertNull(SafTreePaths.bestTree("/storage/1A2B-3C4D/Films", grants))
    }
}
