package com.beatflowy.app.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SongDao {
    @Query("SELECT * FROM songs")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<String>)

    @Query("DELETE FROM songs WHERE LOWER(driveAccountEmail) = LOWER(:email)")
    suspend fun deleteSongsByAccount(email: String)

    @Query("DELETE FROM songs WHERE folder = :folderPath OR folder LIKE :folderPath || '/%'")
    suspend fun deleteSongsInFolder(folderPath: String)

    @Query("SELECT * FROM songs WHERE LOWER(driveAccountEmail) = LOWER(:email)")
    suspend fun getSongsByAccount(email: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE source = 'GDRIVE'")
    suspend fun getAllCloudSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE LOWER(driveAccountEmail) IN (:emails)")
    suspend fun getSongsByAccounts(emails: List<String>): List<SongEntity>
}
