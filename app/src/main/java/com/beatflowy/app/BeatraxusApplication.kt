package com.beatflowy.app

import android.app.Application
import androidx.room.Room
import com.beatflowy.app.model.AppDatabase

class BeatraxusApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "beatraxus_db"
        ).fallbackToDestructiveMigration()
         .build()
    }

    override fun onCreate() {
        super.onCreate()
    }
}
