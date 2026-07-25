package com.beatraxus.app.repository

import com.beatraxus.app.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookmarkRepository(private val bookmarkDao: BookmarkDao) {
    fun getBookmarks(songId: String): Flow<List<BookmarkEntity>> =
        bookmarkDao.getBookmarksForSong(songId)

    suspend fun addBookmark(songId: String, timeMs: Long, label: String) {
        bookmarkDao.insertBookmark(BookmarkEntity(songId = songId, timeMs = timeMs, label = label))
    }

    suspend fun deleteBookmark(id: Long) {
        bookmarkDao.deleteBookmark(id)
    }
}

class ChapterRepository(private val chapterDao: ChapterDao) {
    fun getChapters(songId: String): Flow<List<ChapterEntity>> =
        chapterDao.getChaptersForSong(songId)

    suspend fun addChapter(songId: String, startMs: Long, label: String, color: Int) {
        chapterDao.insertChapter(ChapterEntity(songId = songId, startMs = startMs, label = label, color = color))
    }
    
    // Stub for future smart detection
    suspend fun detectChapters(song: Song) {
        // Mocking intro/verse/chorus
        addChapter(song.id, 0L, "Intro", 0xFF6200EE.toInt())
        addChapter(song.id, 30000L, "Verse 1", 0xFF03DAC6.toInt())
        addChapter(song.id, 90000L, "Chorus", 0xFFCF6679.toInt())
    }
}

class HighlightRepository(private val highlightDao: HighlightDao) {
    fun getHighlights(songId: String): Flow<List<HighlightEntity>> =
        highlightDao.getHighlightsForSong(songId)

    suspend fun addHighlight(songId: String, timeMs: Long, type: String, label: String) {
        highlightDao.insertHighlight(HighlightEntity(songId = songId, timeMs = timeMs, type = type, label = label))
    }

    // Stub for future AI highlight detection
    suspend fun detectHighlights(songId: String) {
        addHighlight(songId, 120000L, "peak", "Vocal Peak")
    }
}

class LoudnessRepository(private val loudnessDao: LoudnessDao) {
    suspend fun getLoudness(songId: String): LoudnessEntity? = withContext(Dispatchers.IO) {
        loudnessDao.getLoudnessForSong(songId)
    }

    suspend fun saveLoudness(songId: String, data: FloatArray) {
        loudnessDao.insertLoudness(LoudnessEntity(songId = songId, data = data))
    }
    
    // Stub for future RMS/LUFS analysis
    suspend fun analyzeLoudness(songId: String): FloatArray {
        // Return fake data for now: 100 points
        val random = kotlin.random.Random(songId.hashCode())
        return FloatArray(100) { random.nextFloat() * 0.8f + 0.2f }
    }
}
