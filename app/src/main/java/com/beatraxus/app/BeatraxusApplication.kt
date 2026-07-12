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
        ).addMigrations(AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15)
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

        // Initialize TDLib early to ensure it runs once and is ready for use
        TdLibManager.initialize(this)
    }

    fun clearTelegramCache() {
        try {
            val tdlibFilesDir = java.io.File(cacheDir, "tdlib/files")
            if (tdlibFilesDir.exists()) {
                tdlibFilesDir.deleteRecursively()
                tdlibFilesDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearTemporaryCache() {
        try {
            val cacheDirRoot = cacheDir
            val cloudCacheDir = java.io.File(cacheDirRoot, "cloud_cache")
            if (cloudCacheDir.exists()) {
                cloudCacheDir.listFiles()?.forEach { 
                    // Only clear files that are definitely from Telegram if we want to be safe,
                    // but the user's current code clears everything.
                    // I will leave the GDrive part alone as requested "dont touch gdrive cache related codes".
                    it.delete() 
                }
            }
            // Also clear the LRU map preferences
            getSharedPreferences("playback_lru_prefs", MODE_PRIVATE).edit().clear().apply()

            // Clear TDLib's own raw download cache
            clearTelegramCache()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
