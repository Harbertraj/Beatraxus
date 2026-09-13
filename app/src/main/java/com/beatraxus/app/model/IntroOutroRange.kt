package com.beatraxus.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(tableName = "intro_outro_ranges")
data class IntroOutroRange(
    @PrimaryKey val folderPath: String,
    val startMs: Long,
    val endMs: Long,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Dao
interface IntroOutroDao {
    @Query("SELECT * FROM intro_outro_ranges WHERE folderPath = :folderPath")
    suspend fun getRangeForFolder(folderPath: String): IntroOutroRange?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRange(range: IntroOutroRange)

    @Query("DELETE FROM intro_outro_ranges WHERE folderPath = :folderPath")
    suspend fun deleteRange(folderPath: String)
}
