package com.example

import android.app.Application
import androidx.room.Room
import com.example.db.AppDatabase
import com.example.db.BlockedAppRepository

class FlightModeApp : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "flight_mode_database"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    val repository: BlockedAppRepository by lazy {
        BlockedAppRepository(database.blockedAppDao())
    }
}
