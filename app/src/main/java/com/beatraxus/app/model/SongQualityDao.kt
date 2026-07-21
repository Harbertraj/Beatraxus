package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongQualityDao {
    @Query("SELECT * FROM song_quality WHERE songId = :songId")
    suspend fun getQualityForSong(songId: String): SongQualityEntity?

    @Query("SELECT * FROM song_quality WHERE songId = :songId")
    fun getQualityFlow(songId: String): Flow<SongQualityEntity?>

    @Query("SELECT * FROM song_quality")
    fun getAllQualityFlow(): Flow<List<SongQualityEntity>>

    @Upsert
    suspend fun upsertQuality(quality: SongQualityEntity)

    @Query("DELETE FROM song_quality WHERE songId = :songId")
    suspend fun deleteQuality(songId: String)

    @Query("SELECT COUNT(*) FROM song_quality")
    suspend fun getQualityCount(): Int
}
