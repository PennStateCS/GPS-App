package com.example.surveyingapp.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.surveyingapp.BuildConfig
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.data.local.entity.ModelEntity

@Database(
    entities = [CoordinateEntity::class, ModelEntity::class],
    // v7: the v6 schema was revised during development (pointCode/pointType removed).
    // v8: models table gained embedded-location columns.
    // v9: added indices on rtkStatus, icon, horizontalAccuracyM for faster filtering.
    // v10: coordinates gained explicit model association (modelId/iconKey/renderEnabled,
    //      createdAt/updatedAt) + per-coordinate model placement; models gained health
    //      (checksum/isValid/validationErrorsJson) + default placement/units metadata.
    version = 10,
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
                    // Full, contiguous migration chain v2 -> v9. Every step has an explicit
                    // migration (3->4 and 6->7 are documented no-op bridges), so release builds
                    // upgrade existing installs without ever losing saved coordinates/models.
                    .addMigrations(
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        Migration4To5(),
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    // Destructive fallback is DEBUG-ONLY and never runs in release. It only
                    // matters for the pre-v2 floor (no schema was exported before v3) and for
                    // developers jumping between in-progress schema revisions. Removing it
                    // entirely would crash dev installs coming from a pre-v2 database, so it is
                    // kept but confined to debug builds.
                    .also { if (BuildConfig.DEBUG) it.fallbackToDestructiveMigration(dropAllTables = true) }
                    .build().also { INSTANCE = it }
            }
        private const val DATABASE_NAME = "surveying_app.db"
    }
}
