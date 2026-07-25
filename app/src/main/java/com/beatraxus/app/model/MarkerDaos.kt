package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE songId = :songId ORDER BY timeMs ASC")
    fun getBookmarksForSong(songId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE songId = :songId ORDER BY startMs ASC")
    fun getChaptersForSong(songId: String): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity)
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE songId = :songId ORDER BY timeMs ASC")
    fun getHighlightsForSong(songId: String): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: HighlightEntity)
}

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM loudness_cache WHERE songId = :songId")
    suspend fun getLoudnessForSong(songId: String): LoudnessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoudness(loudness: LoudnessEntity)
}
