package com.example.surveyingapp.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity

@Database(
    entities = [CoordinateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun coordinateDao(): CoordinateDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
        private const val DATABASE_NAME = "surveying_app.db"
    }
}