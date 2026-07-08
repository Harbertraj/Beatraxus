package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM music_folders WHERE state = 'ACTIVE'")
    fun getActiveFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM music_folders WHERE state = 'ACTIVE'")
    suspend fun getActiveFoldersList(): List<FolderEntity>

    @Query("SELECT * FROM music_folders WHERE state = 'BLOCKLISTED'")
    fun getBlocklistedFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query("UPDATE music_folders SET state = :state WHERE path = :path")
    suspend fun updateFolderState(path: String, state: String)

    @Query("DELETE FROM music_folders WHERE path = :path")
    suspend fun deleteFolder(path: String)
}
