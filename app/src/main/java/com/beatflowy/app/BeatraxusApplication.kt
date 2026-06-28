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
        // Initialize TDLib early
        tdLibManager
    }
}
