package com.example.surveyingapp.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coordinates_rtkStatus ON coordinates(rtkStatus)")
    }
}

/**
 * Migration 3 -> 4: no-op.
 *
 * The exported schemas (schemas/.../3.json and 4.json) are byte-identical for both the
 * coordinates and models tables — version 4 was a database version bump with no schema
 * change. This bridge exists only so Room has a continuous migration path from 3 to 4 and
 * never falls back to a destructive migration (which would delete saved coordinates/models).
 * Room's post-migration identity-hash check passes because the table structure is unchanged.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Intentionally empty: schema 3 and schema 4 are identical.
    }
}

/**
 * Migration 4 -> 5: add thumbnailFileName and thumbnailFilePath columns to the models table.
 *
 * Kept as a named class (rather than an `object : Migration` like the others) for historical
 * reasons — [AppDatabase] and the migration tests reference it as `Migration4To5()`.
 */
class Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE models ADD COLUMN thumbnailFileName TEXT")
        db.execSQL("ALTER TABLE models ADD COLUMN thumbnailFilePath TEXT")
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
 * Migration 9 -> 10: additive only. No existing column is dropped or retyped, so all user
 * data is preserved.
 *
 * coordinates gains:
 *  - modelId / iconKey            explicit model association, replacing the icon = "model:<id>"
 *                                 string convention (the legacy icon column is kept untouched).
 *  - renderEnabled                whether a linked model should render (default 1/true).
 *  - createdAt / updatedAt        audit timestamps, backfilled from the existing timestamp.
 *  - model{Scale,Yaw,Pitch,Roll}Deg, modelVerticalOffsetM, modelOriginOffset{X,Y,Z}M
 *                                 per-coordinate placement overrides (nullable = use defaults).
 *
 * models gains:
 *  - checksum, isValid, validationErrorsJson       model-health fields (default isValid = 1).
 *  - defaultScale, defaultYawDeg, originOffset{X,Y,Z}M, boundingBoxJson, units
 *                                 default placement metadata (safe numeric defaults).
 *
 * Backfill: legacy icon values are split into modelId (when icon starts with "model:") or
 * iconKey (otherwise); createdAt/updatedAt are seeded from timestamp. The defaultValue clauses
 * here must match the @ColumnInfo(defaultValue = ...) annotations on the entities so Room's
 * post-migration schema validation passes.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- coordinates: model association ---
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelId TEXT")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN iconKey TEXT")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN renderEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

        // --- coordinates: per-coordinate placement overrides (nullable) ---
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelScale REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelYawDeg REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelPitchDeg REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelRollDeg REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelVerticalOffsetM REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelOriginOffsetXM REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelOriginOffsetYM REAL")
        db.execSQL("ALTER TABLE coordinates ADD COLUMN modelOriginOffsetZM REAL")

        // --- coordinates: backfill association + audit timestamps from existing data ---
        db.execSQL("UPDATE coordinates SET modelId = substr(icon, 7) WHERE icon LIKE 'model:%'")
        db.execSQL("UPDATE coordinates SET iconKey = icon WHERE icon NOT LIKE 'model:%'")
        db.execSQL("UPDATE coordinates SET createdAt = timestamp, updatedAt = timestamp")

        // --- models: health / validation ---
        db.execSQL("ALTER TABLE models ADD COLUMN checksum TEXT")
        db.execSQL("ALTER TABLE models ADD COLUMN isValid INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE models ADD COLUMN validationErrorsJson TEXT")

        // --- models: default placement metadata ---
        db.execSQL("ALTER TABLE models ADD COLUMN defaultScale REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE models ADD COLUMN defaultYawDeg REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE models ADD COLUMN originOffsetXM REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE models ADD COLUMN originOffsetYM REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE models ADD COLUMN originOffsetZM REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE models ADD COLUMN boundingBoxJson TEXT")
        db.execSQL("ALTER TABLE models ADD COLUMN units TEXT")
    }
}

/**
 * Migration 6 -> 7: no-op.
 *
 * The exported schemas (schemas/.../6.json and 7.json) are byte-identical for both the
 * coordinates and models tables — version 7 was a database version bump with no schema
 * change. (The "pointCode/pointType removed" note elsewhere in the codebase is inaccurate;
 * those columns never appear in any exported schema.) This bridge keeps the migration path
 * from 6 to 7 continuous so Room never resorts to a destructive migration.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Intentionally empty: schema 6 and schema 7 are identical.
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
