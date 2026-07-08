package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAnalysisDao {
    @Query("SELECT * FROM song_ai_analysis WHERE songId = :songId")
    suspend fun getAnalysisForSong(songId: String): AiAnalysisEntity?

    @Query("SELECT * FROM song_ai_analysis WHERE songId = :songId")
    fun getAnalysisFlow(songId: String): Flow<AiAnalysisEntity?>

    @Query("SELECT * FROM song_ai_analysis")
    fun getAllAnalysisFlow(): Flow<List<AiAnalysisEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(analysis: AiAnalysisEntity)

    @Query("DELETE FROM song_ai_analysis WHERE songId = :songId")
    suspend fun deleteAnalysis(songId: String)

    @Query("SELECT COUNT(*) FROM song_ai_analysis")
    suspend fun getAnalysisCount(): Int
}
