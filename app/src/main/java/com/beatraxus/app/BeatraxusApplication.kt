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
        ).addMigrations(
            AppDatabase.MIGRATION_11_12, 
            AppDatabase.MIGRATION_12_13, 
            AppDatabase.MIGRATION_13_14, 
            AppDatabase.MIGRATION_14_15, 
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21
        )
         .fallbackToDestructiveMigration(false)
         .build()
    }

    val tdLibManager: TdLibManager by lazy {
        TdLibManager.getInstance(this)
    }

    val driveAccountRepository by lazy { com.beatraxus.app.repository.DriveAccountRepository(this) }
    val dropboxAccountRepository by lazy { com.beatraxus.app.repository.DropboxAccountRepository(this) }
    val onedriveAccountRepository by lazy { com.beatraxus.app.repository.OneDriveAccountRepository(this) }
    val boxAccountRepository by lazy { com.beatraxus.app.repository.BoxAccountRepository(this) }
    val nextcloudAccountRepository by lazy { com.beatraxus.app.repository.NextcloudAccountRepository(this) }
    val smbConnectionRepository by lazy { com.beatraxus.app.repository.SmbConnectionRepository(this) }
    val ftpConnectionRepository by lazy { com.beatraxus.app.repository.FtpConnectionRepository(this) }
    val smbFolderBrowser by lazy { com.beatraxus.app.network.SmbFolderBrowser() }
    val ftpFolderBrowser by lazy { com.beatraxus.app.network.FtpFolderBrowser() }

    val cloudCacheManager by lazy {
        com.beatraxus.app.drive.CloudCacheManager.getInstance(
            this,
            driveAccountRepository,
            dropboxAccountRepository,
            onedriveAccountRepository,
            boxAccountRepository,
            nextcloudAccountRepository,
            smbConnectionRepository,
            ftpConnectionRepository,
            smbFolderBrowser,
            ftpFolderBrowser
        )
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

            // DO NOT clear TDLib's internal raw download cache (clearTelegramCache()) here anymore.
            // TDLib manages its own files and deleting them manually causes "File not found" errors
            // when trying to play songs after an app restart.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
