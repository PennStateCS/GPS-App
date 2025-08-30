package com.example.surveyingapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database class for the entire app.
 *
 * This is the main database class that ties everything together:
 * - Defines which entities (tables) are in the database
 * - Specifies the database version (increment when schema changes)
 * - Provides access to DAOs (Data Access Objects)
 *
 * Room generates the actual database implementation at compile time.
 * The Singleton pattern ensures only one database instance exists.
 */
@Database(entities = [Coordinate::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // Abstract function - Room will implement this automatically
    abstract fun coordinateDao(): CoordinateDao

    companion object {
        // @Volatile ensures all threads see the most up-to-date value
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Gets the singleton database instance.
         * Uses the double-checked locking pattern for thread safety.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,    // Use application context to avoid memory leaks
                    AppDatabase::class.java,
                    "survey_database"             // Database file name
                )
                .fallbackToDestructiveMigration()     // Drop and recreate the database if migration is not possible
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
