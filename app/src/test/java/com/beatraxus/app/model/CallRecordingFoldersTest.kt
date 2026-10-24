package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecordingFoldersTest {

    @Test
    fun `common call recording folders of popular phones are recognised`() {
        listOf(
            "/storage/emulated/0/Recordings/Call",                 // Samsung (Android 11+)
            "/storage/emulated/0/Call",                            // older Samsung
            "/storage/emulated/0/MIUI/sound_recorder/call_rec",    // Xiaomi
            "/storage/emulated/0/Sounds/CallRecord",               // Huawei
            "/storage/emulated/0/Recordings/Call Recordings",      // OnePlus / Oppo
            "/storage/emulated/0/Record/Call",                     // Vivo
            "/storage/emulated/0/ACRCalls",                        // ACR
            "/storage/emulated/0/CubeCallRecorder/Recorded",       // Cube ACR
            "/storage/1234-ABCD/Recordings/Call"                   // on an SD card
        ).forEach { assertTrue("expected a call folder: $it", CallRecordingFolders.isCallRecordingFolder(it)) }
    }

    @Test
    fun `music folders are not mistaken for call recordings`() {
        listOf(
            "/storage/emulated/0/Music",
            "/storage/emulated/0/Music/Call Me Maybe",
            "/storage/emulated/0/Music/Calls",            // a plain "Calls" inside Music is not a recordings folder
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Recordings/Voice Recorder",
            "/storage/emulated/0/Recordings/Interviews",
            "/storage/emulated/0/Music/Recall",
            "/",
            ""
        ).forEach { assertFalse("not a call folder: $it", CallRecordingFolders.isCallRecordingFolder(it)) }
    }

    @Test
    fun `a recording in a sub folder is covered by the outermost call folder`() {
        assertEquals(
            "/storage/emulated/0/Recordings/Call",
            CallRecordingFolders.callRecordingRoot("/storage/emulated/0/Recordings/Call/2024/March")
        )
        assertNull(CallRecordingFolders.callRecordingRoot("/storage/emulated/0/Music/Rock"))
    }

    @Test
    fun `paths are compared by folder, ignoring case and a trailing slash`() {
        assertTrue(CallRecordingFolders.isInside("/a/b/c", "/a/b"))
        assertTrue(CallRecordingFolders.isInside("/A/B/", "/a/b"))
        assertTrue(CallRecordingFolders.isInside("/a/b", "/a/b/"))
        assertFalse(CallRecordingFolders.isInside("/a/bc", "/a/b"))   // a sibling with the same start
        assertFalse(CallRecordingFolders.isInside("/a", "/a/b"))
        assertFalse(CallRecordingFolders.isInside("/a", ""))
    }

    @Test
    fun `a full scan finds each call folder once`() {
        val found = CallRecordingFolders.foldersToExclude(
            songFolders = listOf(
                "/storage/emulated/0/Music/Rock",
                "/storage/emulated/0/Recordings/Call",
                "/storage/emulated/0/Recordings/Call/2024",
                "/storage/emulated/0/Recordings/Call",
                "/storage/emulated/0/ACRCalls"
            ),
            userAllowed = emptyList(),
            alreadyExcluded = emptyList()
        )
        assertEquals(listOf("/storage/emulated/0/Recordings/Call", "/storage/emulated/0/ACRCalls"), found)
    }

    @Test
    fun `a folder the listener added back is never excluded again`() {
        val songFolders = listOf("/storage/emulated/0/Recordings/Call", "/storage/emulated/0/ACRCalls")
        val allowed = listOf("/storage/emulated/0/Recordings/Call")
        assertEquals(
            listOf("/storage/emulated/0/ACRCalls"),
            CallRecordingFolders.foldersToExclude(songFolders, allowed, emptyList())
        )
    }

    @Test
    fun `adding a folder inside or around a call folder also keeps it`() {
        val songFolders = listOf("/storage/emulated/0/Recordings/Call/2024")
        assertTrue(CallRecordingFolders.foldersToExclude(songFolders, listOf("/storage/emulated/0/Recordings/Call/2024"), emptyList()).isEmpty())
        assertTrue(CallRecordingFolders.foldersToExclude(songFolders, listOf("/storage/emulated/0/Recordings/Call"), emptyList()).isEmpty())
    }

    @Test
    fun `an unrelated allowed folder does not protect a call folder`() {
        val songFolders = listOf("/storage/emulated/0/Recordings/Call")
        val found = CallRecordingFolders.foldersToExclude(songFolders, listOf("/storage/emulated/0/ACRCalls"), emptyList())
        assertEquals(listOf("/storage/emulated/0/Recordings/Call"), found)
    }

    @Test
    fun `folders that are already excluded are not reported again`() {
        val songFolders = listOf("/storage/emulated/0/Recordings/Call/2024")
        assertTrue(
            CallRecordingFolders.foldersToExclude(songFolders, emptyList(), listOf("/storage/emulated/0/Recordings/Call")).isEmpty()
        )
    }

    @Test
    fun `no song folders means nothing to exclude`() {
        assertTrue(CallRecordingFolders.foldersToExclude(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
