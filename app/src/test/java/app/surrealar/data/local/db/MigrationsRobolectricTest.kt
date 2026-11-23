package app.surrealar.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import io.github.pennstatecs.geokit.gnss.model.Provider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM (Robolectric) counterpart of the instrumented MigrationTest: builds a real SQLite file at an old
 * version from the exported schema JSON in app/schemas, seeds it, then opens it with Room and the
 * production migration chain. Room validates the migrated tables against the current entities, so a
 * migration that produces the wrong schema fails here; the assertions check the saved data survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationsRobolectricTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<AppDatabase>()

    /** Same chain, same order as AppDatabase.getDatabase. */
    private val allMigrations: Array<Migration> = arrayOf(
        MIGRATION_2_3, MIGRATION_3_4, Migration4To5(), MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
    )

    @Before fun clean() { ctx.databaseList().forEach { ctx.deleteDatabase(it) } }

    @After fun close() { opened.forEach { it.close() } }

    private fun schemaJson(version: Int): JSONObject {
        val rel = "schemas/app.surrealar.data.local.db.AppDatabase/$version.json"
        val file = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("exported schema $rel not found (cwd=${File(".").absolutePath})")
        return JSONObject(file.readText())
    }

    /** Creates [name] exactly as Room would have at [schemaVersion], then stamps it [userVersion]. */
    private fun createAt(name: String, schemaVersion: Int, userVersion: Int = schemaVersion, seed: (SQLiteDatabase) -> Unit) {
        val database = schemaJson(schemaVersion).getJSONObject("database")
        val path = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            seed(db)
            db.version = userVersion
        }
    }

    private fun openMigrated(name: String): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(*allMigrations)
            .allowMainThreadQueries()
            .build()
            .also { opened += it }

    private fun SQLiteDatabase.insertBasicCoordinate(id: String, icon: String, ts: Long, provider: String = "INTERNAL") =
        execSQL(
            "INSERT INTO coordinates (id,name,latitude,longitude,altitude,timestamp,icon,color,provider) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(id, "Pt $id", 41.246813579246813, -75.88112233445566, 187.12345678901234, ts, icon, -1, provider),
        )

    private fun SQLiteDatabase.insertBasicModel(id: String) =
        execSQL(
            "INSERT INTO models (id,name,fileName,filePath,fileSize,dateAdded) VALUES (?,?,?,?,?,?)",
            arrayOf<Any>(id, "Model $id", "$id.glb", "/models/$id.glb", 4_000_000_000L, 77L),
        )

    @Test
    fun everyExportedVersion_migratesToCurrent_andKeepsItsRows() = runBlocking {
        for (v in 3..12) {
            val name = "from-$v.db"
            createAt(name, v) { db ->
                db.insertBasicCoordinate("c$v", "ic_pin", 1_000L + v)
                db.insertBasicModel("m$v")
            }
            val room = openMigrated(name)
            val c = room.coordinateDao().getById("c$v") ?: error("coordinate lost migrating from v$v")
            assertEquals("v$v lat", 41.246813579246813, c.latitude, 0.0)
            assertEquals("v$v lon", -75.88112233445566, c.longitude, 0.0)
            assertEquals("v$v alt", 187.12345678901234, c.altitude, 0.0)
            assertEquals(1_000L + v, c.timestamp)
            val m = room.modelDao().getModelById("m$v") ?: error("model lost migrating from v$v")
            assertEquals(4_000_000_000L, m.fileSize)
            assertEquals("/models/m$v.glb", m.filePath)
            assertEquals(13, room.openHelper.readableDatabase.version)
            room.close()
        }
    }

    @Test
    fun fromVersion2_runsTheWholeChain() = runBlocking {
        // No v2 schema was exported; v2 -> v3 only added an index, so the v3 tables stand in for v2.
        createAt("from-2.db", schemaVersion = 3, userVersion = 2) { it.insertBasicCoordinate("old", "model:m1", 5L) }
        val room = openMigrated("from-2.db")
        val c = room.coordinateDao().getById("old")!!
        assertEquals("m1", c.modelId)
        room.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_coordinates_rtkStatus'"
        ).use { assertTrue(it.moveToFirst()) }
    }

    @Test
    fun fromVersion3_keepsEverySurveyField_andBackfillsNewColumns() = runBlocking {
        createAt("full-3.db", 3) { db ->
            db.execSQL(
                "INSERT INTO coordinates (id,name,latitude,longitude,altitude,timestamp,icon,color,provider," +
                    "rtkStatus,satsUsed,satsVisible,hdop,vDop,pDop,horizontalAccuracyM,verticalAccuracyM," +
                    "correctionSource,correctionAgeS,correctionStationId,speedMps,courseDeg,timestampSource," +
                    "multipathIndex,altitudeMsl,geoidSeparationM,crsEpsg,easting,northing,utmZone,note," +
                    "averagedSamples,averageDurationMs,stdLatM,stdLonM,stdAltM,sourceDevice,appVersion) VALUES " +
                    "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    "s", "Survey", 41.1, -75.9, 0.0, 1_763_900_000_123L, "model:bridge", 255, "RS2_TCP",
                    "FIX", 22, 30, 0.7, 0.9, 1.1, 0.012, 0.021,
                    "NTRIP", 1.5, "PAWB", 0.01, 181.5, "NMEA_ZDA",
                    0.2, -32.0, 32.0, 6318, 2_455_000.25, 389_000.75, "18T", "note, with comma",
                    600, 600_000L, 0.003, 0.004, 0.008, "Reach RS2+", "1.0",
                ),
            )
            db.insertBasicCoordinate("pin", "ic_star", 99L, provider = "rs2-external")
            db.insertBasicModel("bridge")
        }
        val room = openMigrated("full-3.db")
        val s = room.coordinateDao().getById("s")!!
        assertEquals(0.0, s.altitude, 0.0)               // a real 0 m stays 0 m
        assertEquals(-32.0, s.altitudeMsl!!, 0.0)
        assertEquals(32.0, s.geoidSeparationM!!, 0.0)
        assertEquals(Provider.EXTERNAL_TCP, s.provider)  // legacy token still reads back
        assertEquals(22, s.satsUsed)
        assertEquals(0.012, s.horizontalAccuracyM!!, 0.0)
        assertEquals(6318, s.crsEpsg)
        assertEquals("18T", s.utmZone)
        assertEquals("note, with comma", s.note)
        assertEquals(600_000L, s.averageDurationMs)
        assertEquals("Reach RS2+", s.sourceDevice)
        // v6 / v10 / v11 / v12 / v13 columns
        assertEquals(null, s.captureMethod)
        assertEquals("bridge", s.modelId)
        assertEquals(null, s.iconKey)
        assertTrue(s.renderEnabled)
        assertEquals(1_763_900_000_123L, s.createdAt)
        assertEquals(1_763_900_000_123L, s.updatedAt)
        assertEquals(null, s.antennaHeightM)
        assertEquals(null, s.modelScale)
        assertEquals(null, s.modelPlacementOrigin)
        assertEquals(null, s.modelMoveEastM)
        assertEquals(null, s.modelMoveUpM)

        val pin = room.coordinateDao().getById("pin")!!
        assertEquals(Provider.EXTERNAL_TCP, pin.provider)
        assertEquals(null, pin.modelId)
        assertEquals("ic_star", pin.iconKey)
        assertEquals(99L, pin.createdAt)

        val m = room.modelDao().getModelById("bridge")!!
        assertEquals(null, m.thumbnailFilePath)
        assertEquals(null, m.embeddedLatitude)
        assertTrue(m.isValid)
        assertEquals(1.0, m.defaultScale, 0.0)
        assertEquals(0.0, m.originOffsetZM, 0.0)
        assertEquals(null, m.boundingBoxJson)
    }

    @Test
    fun fromVersion12_keepsPlacementData_andAddsNullMoveOffset() = runBlocking {
        createAt("from-12.db", 12) { db ->
            db.execSQL(
                "INSERT INTO coordinates (id,name,latitude,longitude,altitude,timestamp,icon,color,provider," +
                    "modelId,renderEnabled,createdAt,updatedAt,modelScale,modelYawDeg,antennaHeightM,modelPlacementOrigin) " +
                    "VALUES ('p','Placed',41.0,-76.0,300.5,10,'ic_pin',0,'EXTERNAL_TCP','m1',0,11,12,2.5,-45.0,1.8,'BOTTOM_CENTER')"
            )
        }
        val room = openMigrated("from-12.db")
        val p = room.coordinateDao().getById("p")!!
        assertEquals("m1", p.modelId)
        assertEquals(false, p.renderEnabled)
        assertEquals(11L, p.createdAt)
        assertEquals(12L, p.updatedAt)
        assertEquals(2.5, p.modelScale!!, 0.0)
        assertEquals(-45.0, p.modelYawDeg!!, 0.0)
        assertEquals(1.8, p.antennaHeightM!!, 0.0)
        assertEquals("BOTTOM_CENTER", p.modelPlacementOrigin)
        assertEquals(null, p.modelMoveEastM)
        assertEquals(null, p.modelMoveNorthM)
        assertEquals(null, p.modelMoveUpM)
    }

    @Test
    fun migratedDatabase_carriesTheCurrentIdentityHash() {
        createAt("hash.db", 9) { it.insertBasicCoordinate("h", "ic_pin", 1L) }
        val room = openMigrated("hash.db")
        val expected = schemaJson(13).getJSONObject("database").getString("identityHash")
        room.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getString(0))
        }
    }

    @Test
    fun migrations_declareAContiguousChain() {
        val steps = allMigrations.map { it.startVersion to it.endVersion }
        assertEquals((2 until 13).map { it to it + 1 }, steps)
    }
}
