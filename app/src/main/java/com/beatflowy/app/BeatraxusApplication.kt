package com.beatflowy.app

import android.app.Application
import androidx.room.Room
import com.beatflowy.app.model.AppDatabase
import com.beatflowy.app.telegram.TdLibManager

class BeatraxusApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "beatraxus_db"
        ).addMigrations(AppDatabase.MIGRATION_11_12)
         .fallbackToDestructiveMigration()
         .build()
    }

    val tdLibManager: TdLibManager by lazy {
        TdLibManager.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Clear temporary cloud cache on app start (effectively clearing "on close")
        clearTemporaryCache()
    }

    private fun clearTemporaryCache() {
        try {
            val cacheDir = java.io.File(cacheDir, "cloud_cache")
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
            // Also clear the LRU map preferences
            getSharedPreferences("playback_lru_prefs", MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
