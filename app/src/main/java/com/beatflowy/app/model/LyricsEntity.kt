package com.beatflowy.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: String,
    val lyrics: String, // Raw LRC or plain text
    val timestamp: Long = System.currentTimeMillis()
)
