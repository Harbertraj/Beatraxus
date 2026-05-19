package com.beatflowy.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "music_folders")
data class FolderEntity(
    @PrimaryKey val path: String,
    val state: String = "ACTIVE"
) {
    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_BLOCKLISTED = "BLOCKLISTED"
    }
}
