package com.beatraxus.app.model

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: String)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun getLyrics(songId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)
}

@Dao
interface RecentlyPlayedDao {
    @Query("SELECT * FROM recently_played ORDER BY timestamp DESC")
    fun getAllRecentlyPlayed(): Flow<List<RecentlyPlayedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRecentlyPlayed(item: RecentlyPlayedEntity)

    @Query("DELETE FROM recently_played WHERE songId = :songId")
    suspend fun removeRecentlyPlayed(songId: String)
}

@Database(entities = [PlaylistEntity::class, FavoriteEntity::class, SongEntity::class, RecentlyPlayedEntity::class, LyricsEntity::class, FolderEntity::class, AiAnalysisEntity::class], version = 13, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun songDao(): SongDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun folderDao(): FolderDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun aiAnalysisDao(): AiAnalysisDao

    companion object {
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN telegramChatId INTEGER")
                db.execSQL("ALTER TABLE songs ADD COLUMN telegramMessageId INTEGER")
                db.execSQL("ALTER TABLE songs ADD COLUMN telegramFileId INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN albumArtFetchAttempted INTEGER NOT NULL DEFAULT 0")
                // Reset the flag for cloud songs that are missing art so they get
                // picked up by the new enrichment pass on the next scan.
                db.execSQL(
                    "UPDATE songs SET albumArtFetchAttempted = 0 " +
                    "WHERE albumArtUriString IS NULL AND (source = 'GDRIVE' OR source = 'TELEGRAM')"
                )
            }
        }
    }
}
