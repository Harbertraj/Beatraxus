package com.beatraxus.app

import android.app.Application
import androidx.room.Room
import com.beatraxus.app.model.AppDatabase
import com.beatraxus.app.telegram.TdLibManager

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

        // Clear temporary cloud cache on app start
        clearTemporaryCache()
    }

    private fun clearTemporaryCache() {
        try {
            val cacheDirRoot = cacheDir
            val cloudCacheDir = java.io.File(cacheDirRoot, "cloud_cache")
            if (cloudCacheDir.exists()) {
                cloudCacheDir.listFiles()?.forEach { it.delete() }
            }
            // Also clear the LRU map preferences
            getSharedPreferences("playback_lru_prefs", MODE_PRIVATE).edit().clear().apply()

            // NEW: also clear TDLib's own raw download cache
            val tdlibFilesDir = java.io.File(cacheDirRoot, "tdlib/files")
            if (tdlibFilesDir.exists()) {
                tdlibFilesDir.deleteRecursively()
                tdlibFilesDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
