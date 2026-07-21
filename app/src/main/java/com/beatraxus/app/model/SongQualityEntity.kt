package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "song_quality",
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
data class SongQualityEntity(
    @PrimaryKey val songId: String,

    // Resolution / codec (mirrors Song/SongEntity, snapshotted at analysis time)
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val codec: String,

    // Loudness & dynamics (from AudioAnalyzer)
    val lufs: Float,
    val dynamicRange: Float,
    val truePeakDb: Float,
    val clippedSamplePct: Float,
    val stereoWidth: Float,
    val freqRangeLowHz: Float,
    val freqRangeHighHz: Float,

    // Derived
    val qualityScore: Int,   // 0-100
    val qualityTier: String, // "Excellent" / "Good" / "Fair" / "Poor"

    val analysisVersion: Int,
    val lastAnalyzed: Long
)
