package com.example.surveyingapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Coordinate::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun coordinateDao(): CoordinateDao
    // Backward compatibility for legacy code expecting pointDao()
    fun pointDao(): CoordinateDao = coordinateDao()

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try { database.execSQL("ALTER TABLE points RENAME TO coordinates") } catch (_: Exception) {}
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "survey_database"
                )
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
