package com.beatraxus.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.beatraxus.app.addons.AddonManager
import com.beatraxus.app.model.AppDatabase
import com.beatraxus.app.telegram.TdLibManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BeatraxusApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
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
            AppDatabase.MIGRATION_20_21,
            AppDatabase.MIGRATION_21_22,
            AppDatabase.MIGRATION_22_23,
            AppDatabase.MIGRATION_23_24,
            AppDatabase.MIGRATION_24_25,
            AppDatabase.MIGRATION_25_26,
            AppDatabase.MIGRATION_26_27,
            AppDatabase.MIGRATION_27_28,
            AppDatabase.MIGRATION_28_29,
            AppDatabase.MIGRATION_29_30,
            AppDatabase.MIGRATION_30_31,
            AppDatabase.MIGRATION_31_32
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

    // Released once clearTemporaryCache() has finished (it runs on a background thread, see onCreate).
    private val cacheCleanupDone = CountDownLatch(1)

    val cloudCacheManager by lazy {
        // The startup cleanup deletes files under cloud_cache; wait (briefly, it is only a few file deletes) for it so the cache
        // manager never starts using files the cleanup is about to delete.
        cacheCleanupDone.await(1, TimeUnit.SECONDS)
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

        // Cheap, and other code assumes it has run, so it stays on the main thread.
        AddonManager.init(this)

        // Everything below does disk I/O or loads a large native library (TDLib). It used to run
        // right here on the main thread, which delays the process's first frame and starves the
        // main thread while MainActivity is already asking for AudioPlaybackService to go
        // foreground (the cause of the "stuck on open" hangs and of the occasional
        // "did not call startForeground() in time" crash). Run it on a background thread instead.
        val startup = Executors.newSingleThreadExecutor { r -> Thread(r, "beatraxus-startup") }
        startup.execute {
            // Clear temporary cloud cache on app start
            try {
                clearTemporaryCache()
            } finally {
                cacheCleanupDone.countDown()
            }
        }
        startup.execute {
            // Initialize TDLib early to ensure it runs once and is ready for use. getInstance() is
            // synchronized, so a concurrent first use from the UI thread just waits for it.
            try {
                TdLibManager.initialize(this)
            } catch (t: Throwable) {
                Log.e("BeatraxusApplication", "TDLib initialization failed", t)
            }
        }
        startup.shutdown() // queued tasks still run; the thread exits afterwards
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
