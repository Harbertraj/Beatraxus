package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_folders")
data class VideoFolderEntity(
    @PrimaryKey val path: String,
    val state: String = "ACTIVE",
    val lastModified: Long = 0L
) {
    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_BLOCKLISTED = "BLOCKLISTED"
    }
}
