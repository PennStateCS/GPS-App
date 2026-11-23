package app.surrealar.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.surrealar.data.repository.impl.CoordinateRepositoryImpl
import app.surrealar.domain.model.Coordinate
import app.surrealar.ui.openinar.SaveModelMoveUseCase
import io.github.pennstatecs.geokit.ar.model.ModelMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saving a Move model offset is one transaction: no lost updates, a failed write changes nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateUpdateByIdRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CoordinateRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = CoordinateRepositoryImpl(db.coordinateDao())
    }

    @After
    fun tearDown() = db.close()

    private val point = Coordinate(
        id = "p", name = "P", latitude = 41.246813579, longitude = -75.881234567, altitude = 210.123,
        timestamp = 1_000L, icon = "ic_pin", color = 0, modelMoveEastM = 0.5, altitudeMsl = 243.4,
    )

    @Test
    fun saveMove_changesOnlyTheModelOffsets() = runBlocking {
        repo.insert(point)
        val before = repo.getById("p")!!   // as stored (the mapper fills createdAt/updatedAt)
        SaveModelMoveUseCase(repo)("p", ModelMove(eastM = 1f, northM = -0.25f, yawDeg = 15f), fallbackYawDeg = 0f, nowMs = 5_000L)
        val c = repo.getById("p")!!
        assertEquals(1.5, c.modelMoveEastM!!, 1e-9)
        assertEquals(-0.25, c.modelMoveNorthM!!, 1e-9)
        assertEquals(15.0, c.modelYawDeg!!, 1e-9)
        assertEquals(5_000L, c.updatedAt)
        assertEquals(point.latitude.toRawBits(), c.latitude.toRawBits())
        assertEquals(point.longitude.toRawBits(), c.longitude.toRawBits())
        assertEquals(point.altitude.toRawBits(), c.altitude.toRawBits())
        assertEquals(before, c.copy(modelMoveEastM = 0.5, modelMoveNorthM = null, modelYawDeg = null, updatedAt = before.updatedAt))
    }

    @Test
    fun concurrentSaves_noneAreLost() = runBlocking {
        repo.insert(point.copy(modelMoveEastM = null))
        val save = SaveModelMoveUseCase(repo)
        (1..20).map { async(Dispatchers.IO) { save("p", ModelMove(eastM = 0.1f), fallbackYawDeg = 0f) } }.awaitAll()
        assertEquals(2.0, repo.getById("p")!!.modelMoveEastM!!, 1e-9)
    }

    @Test
    fun failedWrite_leavesTheRowUnchanged() = runBlocking {
        repo.insert(point)
        val before = repo.getById("p")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER fail_update BEFORE UPDATE ON coordinates BEGIN SELECT RAISE(ABORT, 'boom'); END"
        )
        val r = runCatching { SaveModelMoveUseCase(repo)("p", ModelMove(eastM = 1f), fallbackYawDeg = 0f) }
        assertTrue(r.isFailure)
        assertEquals(before, repo.getById("p"))
    }

    @Test
    fun updateById_missingRow_returnsNull() = runBlocking {
        assertNull(repo.updateById("nope") { it.copy(name = "x") })
    }
}
