package com.example.surveyingapp.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 4 -> 5: add thumbnailFileName and thumbnailFilePath columns to models table
 */
class Migration4To5 : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE models ADD COLUMN thumbnailFileName TEXT")
        database.execSQL("ALTER TABLE models ADD COLUMN thumbnailFilePath TEXT")
    }
}

