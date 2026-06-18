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
 * Migration 8 -> 9: add indices on rtkStatus, icon, and horizontalAccuracyM to speed up
 * the common filter queries.  Uses IF NOT EXISTS so re-running is safe (e.g. rtkStatus was
 * already added by MIGRATION_2_3).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coordinates_rtkStatus ON coordinates(rtkStatus)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coordinates_icon ON coordinates(icon)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coordinates_horizontalAccuracyM ON coordinates(horizontalAccuracyM)")
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
