package com.beatraxus.app.model

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "artist_art_cache")
data class ArtistArtEntity(
    @PrimaryKey val normalizedKey: String,
    val displayName: String,
    val imageUrl: String?,
    val fetchedAt: Long
)

@Dao
interface ArtistArtDao {
    @Query("SELECT * FROM artist_art_cache WHERE normalizedKey = :key")
    suspend fun get(key: String): ArtistArtEntity?

    @Query("SELECT * FROM artist_art_cache")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ArtistArtEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ArtistArtEntity)
}
