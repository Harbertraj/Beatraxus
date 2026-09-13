package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "video_chapters",
    indices = [Index(value = ["videoId"])]
)
data class VideoChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val timestampMs: Long,
    val label: String,
    val thumbnailPath: String? = null
)

@Dao
interface VideoChapterDao {
    @Query("SELECT * FROM video_chapters WHERE videoId = :videoId ORDER BY timestampMs ASC")
    fun getChaptersForVideo(videoId: String): Flow<List<VideoChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<VideoChapterEntity>)

    @Query("DELETE FROM video_chapters WHERE videoId = :videoId")
    suspend fun deleteChaptersForVideo(videoId: String)
}
