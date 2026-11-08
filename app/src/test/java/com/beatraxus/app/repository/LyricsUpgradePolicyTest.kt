package com.beatraxus.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsUpgradePolicyTest {

    @Test fun betterTypeReplacesWorse() {
        assertTrue(LyricsUpgradePolicy.shouldApply(LyricsType.PLAIN, false, LyricsType.SYNCED))
        assertTrue(LyricsUpgradePolicy.shouldApply(LyricsType.SYNCED, false, LyricsType.WORD_BY_WORD))
        assertTrue(LyricsUpgradePolicy.shouldApply(LyricsType.PLAIN, false, LyricsType.WORD_BY_WORD))
    }

    @Test fun equalOrWorseNeverReplaces() {
        assertFalse(LyricsUpgradePolicy.shouldApply(LyricsType.SYNCED, false, LyricsType.SYNCED))
        assertFalse(LyricsUpgradePolicy.shouldApply(LyricsType.WORD_BY_WORD, false, LyricsType.SYNCED))
        assertFalse(LyricsUpgradePolicy.shouldApply(LyricsType.SYNCED, false, LyricsType.PLAIN))
        assertFalse(LyricsUpgradePolicy.shouldApply(LyricsType.PLAIN, false, LyricsType.PLAIN))
    }

    @Test fun nothingYetAcceptsAnything() {
        assertTrue(LyricsUpgradePolicy.shouldApply(null, false, LyricsType.PLAIN))
    }

    @Test fun lyricsPickedByHandAreNeverReplaced() {
        assertFalse(LyricsUpgradePolicy.shouldApply(LyricsType.PLAIN, true, LyricsType.WORD_BY_WORD))
        assertFalse(LyricsUpgradePolicy.shouldApply(null, true, LyricsType.WORD_BY_WORD))
    }

    @Test fun bestTypeIgnoresMissingSources() {
        assertNull(LyricsUpgradePolicy.bestType(null, null))
        assertEquals(LyricsType.SYNCED, LyricsUpgradePolicy.bestType(null, LyricsType.PLAIN, LyricsType.SYNCED))
    }

    @Test fun windowIsPlayingSongPlusNextThree() {
        assertEquals(listOf("c", "n1", "n2", "n3"), LyricsUpgradePolicy.window("c", listOf("n1", "n2", "n3", "n4", "n5")))
        assertEquals(listOf("n1", "n2"), LyricsUpgradePolicy.window(null, listOf("n1", "n2")))
        // repeat-one lists the playing song as its own next: no duplicate
        assertEquals(listOf("c", "n2"), LyricsUpgradePolicy.window("c", listOf("c", "n2"), next = 2))
        assertTrue(LyricsUpgradePolicy.window(null, emptyList()).isEmpty())
    }
}
