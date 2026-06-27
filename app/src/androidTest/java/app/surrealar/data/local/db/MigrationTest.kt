package app.surrealar.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration tests. These guard the most dangerous part of the schema work:
 * upgrading a real SQLite database must preserve user data and run the documented backfill.
 *
 * Requires a connected device/emulator (`./gradlew connectedDebugAndroidTest`). The exported
 * schemas in app/schemas are packaged as androidTest assets (see build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate9To10_preservesData_andBackfillsModelLink() {
        // Seed a v9 database: one model-linked coordinate (legacy icon), one icon coordinate, one model.
        helper.createDatabase(testDb, 9).apply {
            execSQL(
                "INSERT INTO coordinates (id,name,latitude,longitude,altitude,timestamp,icon,color,provider) " +
                    "VALUES ('c1','Pt',41.0,-76.0,10.0,555,'model:m1',-1,'INTERNAL')"
            )
            execSQL(
                "INSERT INTO coordinates (id,name,latitude,longitude,altitude,timestamp,icon,color,provider) " +
                    "VALUES ('c2','Pt2',42.0,-77.0,11.0,666,'ic_pin',-1,'INTERNAL')"
            )
            execSQL(
                "INSERT INTO models (id,name,fileName,filePath,fileSize,dateAdded) " +
                    "VALUES ('m1','M','m.glb','/m.glb',1,0)"
            )
            close()
        }

        // Run the migration and validate the resulting schema matches the exported v10 schema.
        val db = helper.runMigrationsAndValidate(testDb, 10, true, MIGRATION_9_10)

        // Legacy model row → modelId backfilled, iconKey null, createdAt seeded from timestamp.
        db.query("SELECT modelId, iconKey, createdAt, renderEnabled FROM coordinates WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("m1", c.getString(0))
            assertTrue(c.isNull(1))
            assertEquals(555L, c.getLong(2))
            assertEquals(1, c.getInt(3))
        }

        // Icon row → iconKey backfilled, modelId null.
        db.query("SELECT modelId, iconKey FROM coordinates WHERE id='c2'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals("ic_pin", c.getString(1))
        }

        // Model defaults applied.
        db.query("SELECT defaultScale, isValid FROM models WHERE id='m1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1.0, c.getDouble(0), 1e-9)
            assertEquals(1, c.getInt(1))
        }
        db.close()
    }

    @Test
    fun fullChain_3To10_isContiguous() {
        // The earliest exported schema is v3; it must upgrade all the way to v10 without a
        // destructive fallback (exercises the 3->4 and 6->7 no-op bridges).
        helper.createDatabase(testDb, 3).apply { close() }
        helper.runMigrationsAndValidate(
            testDb, 10, true,
            MIGRATION_3_4, Migration4To5(), MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
        ).close()
    }
}
