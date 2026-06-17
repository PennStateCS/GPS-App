package com.example.surveyingapp.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coordinates_rtkStatus ON coordinates(rtkStatus)")
    }
}

/**
 * Migration 5 -> 6: add auto-populated capture-method provenance to coordinates.
 * Nullable, so existing rows migrate cleanly with NULL values.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE coordinates ADD COLUMN captureMethod TEXT")
    }
}

/**
 * Migration 7 -> 8: persist the embedded geographic origin extracted from georeferenced
 * GLBs (reprojection erases the in-file signal, so we store it on the model). All nullable.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE models ADD COLUMN embeddedLatitude REAL")
        db.execSQL("ALTER TABLE models ADD COLUMN embeddedLongitude REAL")
        db.execSQL("ALTER TABLE models ADD COLUMN embeddedAltitudeM REAL")
    }
}
