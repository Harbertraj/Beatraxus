package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val timeMs: Long,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapters",
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
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val startMs: Long,
    val label: String,
    val color: Int // ARGB Int
)

@Entity(
    tableName = "highlights",
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
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val timeMs: Long,
    val type: String, // "chorus", "solo", etc.
    val label: String
)

@Entity(
    tableName = "loudness_cache",
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
data class LoudnessEntity(
    @PrimaryKey val songId: String,
    val data: FloatArray,
    val lastAnalyzed: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LoudnessEntity
        if (songId != other.songId) return false
        if (!data.contentEquals(other.data)) return false
        return lastAnalyzed == other.lastAnalyzed
    }

    override fun hashCode(): Int {
        var result = songId.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + lastAnalyzed.hashCode()
        return result
    }
}
