package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiEqCorrectionDao {
    @Query("SELECT * FROM ai_eq_corrections WHERE genre = :genre AND mood = :mood")
    suspend fun getCorrection(genre: String, mood: String): AiEqCorrectionEntity?

    @Query("SELECT * FROM ai_eq_corrections")
    fun getAllCorrections(): Flow<List<AiEqCorrectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: AiEqCorrectionEntity)

    @Query("DELETE FROM ai_eq_corrections")
    suspend fun resetAiLearning()
}
