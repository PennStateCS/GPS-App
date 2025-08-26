package com.example.surveyingapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
@Database(entities = [Coordinate::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // Abstract function - Room will implement this automatically
    abstract fun coordinateDao(): CoordinateDao

    // Backward compatibility for legacy code expecting pointDao()
    fun pointDao(): CoordinateDao = coordinateDao()

    companion object {
        // @Volatile ensures all threads see the most up-to-date value
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Database migration from version 2 to 3.
         * This handles schema changes when upgrading the app.
         * In this case, we renamed the table from "points" to "coordinates".
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Safely try to rename the table (might not exist in all cases)
                try {
                    database.execSQL("ALTER TABLE points RENAME TO coordinates")
                } catch (_: Exception) {}
            }
        }

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
                .addMigrations(MIGRATION_2_3)     // Handle database upgrades
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
