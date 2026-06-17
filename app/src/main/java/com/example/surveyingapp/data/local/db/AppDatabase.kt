package com.example.surveyingapp.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.data.local.entity.ModelEntity

@Database(
    entities = [CoordinateEntity::class, ModelEntity::class],
    // v7: the v6 schema was revised during development (pointCode/pointType removed).
    // v8: models table gained embedded-location columns.
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun coordinateDao(): CoordinateDao
    abstract fun modelDao(): ModelDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_2_3, Migration4To5(), MIGRATION_5_6, MIGRATION_7_8)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { INSTANCE = it }
            }
        private const val DATABASE_NAME = "surveying_app.db"
    }
}
