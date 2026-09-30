package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: String,
    val lyrics: String, // Raw LRC or plain text
    val syncOffset: Long = 0L, // User-defined offset in ms
    val timestamp: Long = System.currentTimeMillis(),
    val providerId: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    val isValidated: Boolean = false,
    val durationMs: Long? = null
)
