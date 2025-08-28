package com.example.surveyingapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

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
@Database(entities = [Coordinate::class, PositionEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // Abstract function - Room will implement this automatically
    abstract fun coordinateDao(): CoordinateDao
    abstract fun positionDao(): PositionDao

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
         * Database migration from version 3 to 4.
         * This handles schema changes when upgrading the app.
         * In this case, we are creating a new table "positions".
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS positions (id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, altEllipsoidalM REAL, accuracyM REAL, bearingDeg REAL, speedMps REAL, provider TEXT NOT NULL, rtkStatus TEXT, satsUsed INTEGER, hdop REAL)"
                )
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)     // Handle database upgrades
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Entity class representing a row in the "positions" table.
 */
@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val altEllipsoidalM: Double?,
    val accuracyM: Double?,
    val bearingDeg: Double?,
    val speedMps: Double?,
    val provider: String,
    val rtkStatus: String?,
    val satsUsed: Int?,
    val hdop: Double?
)

/**
 * DAO (Data Access Object) for the PositionEntity.
 * Defines methods for accessing the "positions" table.
 */
@Dao
interface PositionDao {
    @Insert
    suspend fun insert(e: PositionEntity)

    @Query("SELECT * FROM positions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<PositionEntity>
}
