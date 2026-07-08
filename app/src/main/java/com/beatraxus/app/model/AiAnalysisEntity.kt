package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "song_ai_analysis",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["songId"])]
)
data class AiAnalysisEntity(
    @PrimaryKey val songId: String,
    
    // Genre
    val genre: String,
    val genreConfidence: Float,
    val secondaryGenre: String?,
    val secondaryGenreConfidence: Float?,
    
    // Language
    val language: String,
    val languageConfidence: Float,
    
    // Mood
    val mood: String,
    val moodConfidence: Float,
    
    // Loudness & Dynamics
    val lufs: Float,
    val rms: Float,
    val peak: Float,
    val dynamicRange: Float,
    
    // Spectral features
    val bassScore: Float,
    val midScore: Float,
    val trebleScore: Float,
    val stereoWidth: Float,
    val tempoBpm: Float,
    
    // Generated EQ (10 bands: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k)
    val eq31: Float,
    val eq62: Float,
    val eq125: Float,
    val eq250: Float,
    val eq500: Float,
    val eq1k: Float,
    val eq2k: Float,
    val eq4k: Float,
    val eq8k: Float,
    val eq16k: Float,
    
    val analysisVersion: Int,
    val lastAnalyzed: Long
)
