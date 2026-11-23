package app.surrealar.data.local.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.data.local.dao.ModelDao
import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import app.surrealar.data.repository.impl.CoordinateRepositoryImpl
import app.surrealar.data.repository.mapper.toDomain
import app.surrealar.data.repository.mapper.toEntity
import app.surrealar.domain.model.Coordinate
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real Room-generated DAOs against an in-memory SQLite database (Robolectric).
 *
 * The existing DAO/repository tests use fakes or mocks, so none of the hand-written SQL in
 * [CoordinateDao]/[ModelDao] (or the enum [Converters] as used by Room) was exercised. Saved
 * coordinates are field data, so a broken query or a column that silently drops a value is a
 * data-loss bug — these tests guard that layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var coords: CoordinateDao
    private lateinit var models: ModelDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        coords = db.coordinateDao()
        models = db.modelDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        id: String,
        timestamp: Long = 1_000L,
        lat: Double = 41.0,
        lon: Double = -76.0,
        name: String = "Pt $id",
        icon: String = "ic_pin",
        rtk: RtkStatus? = RtkStatus.FIX,
        provider: Provider = Provider.EXTERNAL_TCP,
        hdop: Double? = 0.8,
        hAcc: Double? = 0.02,
        modelId: String? = null,
    ) = CoordinateEntity(
        id = id, name = name, latitude = lat, longitude = lon, altitude = 100.0,
        timestamp = timestamp, icon = icon, color = 0, provider = provider, rtkStatus = rtk,
        hdop = hdop, horizontalAccuracyM = hAcc, modelId = modelId
    )

    // ── Field fidelity through SQLite ────────────────────────────────────────

    private val rich = Coordinate(
        id = "c1", name = "Point A", latitude = 41.347821234, longitude = -76.022619876, altitude = 399.0213,
        timestamp = 1_700_000_000_123L, icon = "ic_pin", color = -12345,
        provider = "rs2-external", rtkStatus = "FIX", satsUsed = 22, satsVisible = 30,
        hdop = 0.7, vDop = 0.9, pDop = 1.1, horizontalAccuracyM = 0.012, verticalAccuracyM = 0.031,
        correctionSource = "NTRIP", correctionAgeS = 1.2, correctionStationId = "0123",
        altitudeMsl = 365.0, geoidSeparationM = 34.0, speedMps = 0.5, courseDeg = 84.4,
        timestampSource = "NMEA_ZDA", multipathIndex = 0.1, crsEpsg = 4326,
        easting = 500000.123, northing = 4570000.456, utmZone = "18T",
        note = "note with, comma and \"quotes\"", captureMethod = "external_gnss",
        averagedSamples = 150, averageDurationMs = 60_000L,
        stdLatM = 0.01, stdLonM = 0.011, stdAltM = 0.02, sourceDevice = "RS2+", appVersion = "1.0",
        modelId = "m1", iconKey = "ic_pin", renderEnabled = false, createdAt = 1_000L, updatedAt = 2_000L,
        modelScale = 2.5, modelYawDeg = 90.0, modelPitchDeg = 1.0, modelRollDeg = 2.0,
        modelVerticalOffsetM = 1.5, modelOriginOffsetXM = 0.1, modelOriginOffsetYM = 0.2, modelOriginOffsetZM = 0.3,
            modelMoveEastM = 1.25, modelMoveNorthM = -0.5, modelMoveUpM = 0.1
    )

    @Test
    fun repository_roundTripsEveryFieldThroughSqlite() = runBlocking {
        val repo = CoordinateRepositoryImpl(coords)
        repo.insert(rich)

        val fromDb = repo.getById("c1")!!
        // Compare against the mapper-only round trip so this isolates SQLite/Room storage fidelity
        // (mapper semantics are covered by CoordinateMappersTest).
        assertEquals(rich.toEntity().toDomain(), fromDb)
        assertEquals(41.347821234, fromDb.latitude, 0.0)   // no precision loss on REAL columns
        assertEquals(1_700_000_000_123L, fromDb.timestamp)
    }

    @Test
    fun repository_flowsAndCountReflectWrites() = runBlocking {
        val repo = CoordinateRepositoryImpl(coords)
        assertEquals(0, repo.coordinateCountFlow.first())

        repo.insertAll(listOf(rich, rich.copy(id = "c2", timestamp = rich.timestamp + 1)))
        assertEquals(2, repo.count())
        assertEquals(2, repo.coordinateCountFlow.first())
        assertEquals(listOf("c2", "c1"), repo.allCoordinatesFlow.first().map { it.id })

        repo.update(rich.copy(name = "Renamed"))
        assertEquals("Renamed", repo.getById("c1")!!.name)

        repo.deleteById("c1")
        assertNull(repo.getById("c1"))
        repo.deleteAll()
        assertEquals(0, repo.count())
    }

    // ── CoordinateDao queries ────────────────────────────────────────────────

    @Test
    fun insert_replacesOnConflict_ratherThanDuplicating() = runBlocking {
        coords.insert(entity("a", name = "first"))
        coords.insert(entity("a", name = "second"))
        assertEquals(1, coords.count())
        assertEquals("second", coords.getById("a")!!.name)
    }

    @Test
    fun listQueries_areNewestFirst() = runBlocking {
        coords.insertAll(listOf(entity("old", 1L), entity("new", 3L), entity("mid", 2L)))
        assertEquals(listOf("new", "mid", "old"), coords.getAllCoordinatesList().map { it.id })
        assertEquals(listOf("new", "mid"), coords.getRecent(2).map { it.id })
        assertEquals(listOf("new", "mid", "old"), coords.observeAll().first().map { it.id })
    }

    @Test
    fun getBetween_isInclusiveOfBothEnds() = runBlocking {
        coords.insertAll((1L..5L).map { entity("t$it", timestamp = it) })
        assertEquals(listOf("t4", "t3", "t2"), coords.getBetween(2L, 4L).map { it.id })
    }

    @Test
    fun deleteOlderThan_keepsBoundaryRow() = runBlocking {
        coords.insertAll((1L..4L).map { entity("t$it", timestamp = it) })
        coords.deleteOlderThan(3L)
        assertEquals(setOf("t3", "t4"), coords.getAllCoordinatesList().map { it.id }.toSet())
    }

    @Test
    fun deleteByIds_removesOnlyListedRows() = runBlocking {
        coords.insertAll(listOf(entity("a"), entity("b"), entity("c")))
        coords.deleteByIds(listOf("a", "c", "missing"))
        assertEquals(listOf("b"), coords.getAllCoordinatesList().map { it.id })
    }

    @Test
    fun getWithinBBox_filtersByLatAndLon() = runBlocking {
        coords.insertAll(
            listOf(
                entity("inside", lat = 41.0, lon = -76.0),
                entity("northOut", lat = 42.5, lon = -76.0),
                entity("westOut", lat = 41.0, lon = -78.0),
            )
        )
        val hits = coords.getWithinBBox(minLat = 40.0, minLon = -77.0, maxLat = 42.0, maxLon = -75.0)
        assertEquals(listOf("inside"), hits.map { it.id })
    }

    @Test
    fun getNearest_ordersByDistance() = runBlocking {
        coords.insertAll(
            listOf(
                entity("far", lat = 41.1, lon = -76.1),
                entity("near", lat = 41.0001, lon = -76.0001),
                entity("mid", lat = 41.01, lon = -76.01),
            )
        )
        assertEquals(listOf("near"), coords.getNearest(41.0, -76.0).map { it.id })
        assertEquals(listOf("near", "mid", "far"), coords.getNearest(41.0, -76.0, limit = 3).map { it.id })
    }

    @Test
    fun enumFilters_useStoredEnumValues() = runBlocking {
        coords.insertAll(
            listOf(
                entity("fix", rtk = RtkStatus.FIX, provider = Provider.EXTERNAL_TCP),
                entity("float", rtk = RtkStatus.FLOAT, provider = Provider.EXTERNAL_TCP),
                entity("internal", rtk = RtkStatus.SINGLE, provider = Provider.INTERNAL),
            )
        )
        assertEquals(listOf("float"), coords.getByRtkStatus(RtkStatus.FLOAT).map { it.id })
        assertEquals(RtkStatus.FIX, coords.getById("fix")!!.rtkStatus)
    }

    @Test
    fun countByRtkStatus_groupsIncludingNullStatus() = runBlocking {
        coords.insertAll(
            listOf(
                entity("a", rtk = RtkStatus.FIX),
                entity("b", rtk = RtkStatus.FIX),
                entity("c", rtk = RtkStatus.FLOAT),
                entity("d", rtk = null),
            )
        )
        val counts = coords.countByRtkStatus().associate { it.status to it.count }
        assertEquals(mapOf(RtkStatus.FIX to 2, RtkStatus.FLOAT to 1, null to 1), counts)
    }

    @Test
    fun countByModelId_countsExplicitAndLegacyIconLinks() = runBlocking {
        coords.insertAll(
            listOf(
                entity("explicit", modelId = "m1"),
                entity("legacy", icon = "model:m1"),
                entity("otherModel", modelId = "m2"),
                entity("plain"),
            )
        )
        assertEquals(2, coords.countByModelId("m1"))
        assertEquals(1, coords.countByModelId("m2"))
        assertEquals(0, coords.countByModelId("nope"))
    }

    @Test
    fun accuracyFilters_treatNullsDifferently() = runBlocking {
        coords.insertAll(
            listOf(
                entity("good", hdop = 0.5, hAcc = 0.02),
                entity("bad", hdop = 5.0, hAcc = 3.0),
                entity("unknown", hdop = null, hAcc = null),
            )
        )
        // Unknown accuracy must NOT pass an accuracy threshold...
        assertEquals(listOf("good"), coords.getWithMaxAccuracy(0.05).map { it.id })
        // ...but unknown HDOP is deliberately kept by the HDOP filter.
        assertEquals(setOf("good", "unknown"), coords.getWithMaxHdop(1.0).map { it.id }.toSet())
    }

    @Test
    fun searchByName_matchesSubstring() = runBlocking {
        coords.insertAll(
            listOf(entity("1", name = "Corner Stake NE"), entity("2", name = "Benchmark"), entity("3", name = "stake sw"))
        )
        // SQLite LIKE is case-insensitive for ASCII.
        assertEquals(setOf("1", "3"), coords.searchByName("stake").map { it.id }.toSet())
    }

    // ── ModelDao ─────────────────────────────────────────────────────────────

    private fun model(id: String, fileName: String = "$id.glb", dateAdded: Long = 1L) = ModelEntity(
        id = id, name = "Model $id", fileName = fileName, filePath = "/models/$fileName",
        fileSize = 1024L, dateAdded = dateAdded
    )

    @Test
    fun modelDao_crudAndLookups() = runBlocking {
        models.insertModel(model("m1", dateAdded = 1L))
        models.insertModel(model("m2", fileName = "tower.glb", dateAdded = 2L))

        assertEquals(2, models.getModelCount())
        assertEquals(listOf("m2", "m1"), models.getAllModels().first().map { it.id })
        assertEquals("m2", models.getModelByFileName("tower.glb")!!.id)

        // Column defaults defined in the entity are applied.
        val m1 = models.getModelById("m1")!!
        assertEquals(1.0, m1.defaultScale, 0.0)
        assertTrue(m1.isValid)

        models.updateModel(m1.copy(name = "Renamed", embeddedLatitude = 41.5))
        assertEquals("Renamed", models.getModelById("m1")!!.name)
        assertEquals(41.5, models.getModelById("m1")!!.embeddedLatitude!!, 0.0)

        models.deleteModel(models.getModelById("m1")!!)
        models.deleteModelById("m2")
        assertEquals(0, models.observeModelCount().first())
    }

    @Test
    fun modelDao_insertDuplicateId_abortsInsteadOfOverwriting() {
        runBlocking { models.insertModel(model("m1")) }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { models.insertModel(model("m1", fileName = "other.glb")) }
        }
        runBlocking { assertEquals("m1.glb", models.getModelById("m1")!!.fileName) }
    }
}
