package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_eq_corrections",
    indices = [Index(value = ["genre", "mood"], unique = true)]
)
data class AiEqCorrectionEntity(
    @PrimaryKey val id: String, // "${genre}_${mood}"
    val genre: String,
    val mood: String,
    val delta31: Float = 0.0f,
    val delta62: Float = 0.0f,
    val delta125: Float = 0.0f,
    val delta250: Float = 0.0f,
    val delta500: Float = 0.0f,
    val delta1k: Float = 0.0f,
    val delta2k: Float = 0.0f,
    val delta4k: Float = 0.0f,
    val delta8k: Float = 0.0f,
    val delta16k: Float = 0.0f,
    val updateCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
