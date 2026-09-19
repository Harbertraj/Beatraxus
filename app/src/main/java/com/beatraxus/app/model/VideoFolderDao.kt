package com.beatraxus.app.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoFolderDao {
    @Query("SELECT * FROM video_folders WHERE state = 'ACTIVE'")
    fun getActiveFolders(): Flow<List<VideoFolderEntity>>

    @Query("SELECT * FROM video_folders WHERE state = 'ACTIVE'")
    suspend fun getActiveFoldersList(): List<VideoFolderEntity>

    @Query("SELECT * FROM video_folders WHERE state = 'BLOCKLISTED'")
    fun getBlocklistedFolders(): Flow<List<VideoFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: VideoFolderEntity)

    @Query("UPDATE video_folders SET state = :state WHERE path = :path")
    suspend fun updateFolderState(path: String, state: String)

    @Query("UPDATE video_folders SET lastModified = :lastModified WHERE path = :path")
    suspend fun updateLastModified(path: String, lastModified: Long)

    @Query("DELETE FROM video_folders WHERE path = :path")
    suspend fun deleteFolder(path: String)
}
