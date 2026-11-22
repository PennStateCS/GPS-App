package app.surrealar.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.surrealar.data.repository.impl.CoordinateRepositoryImpl
import app.surrealar.domain.model.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Replace-import must be all-or-nothing: a failing insert rolls back the delete too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateReplaceAllRoomTest {

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

    private fun coord(id: String) = Coordinate(
        id = id, name = "Pt $id", latitude = 41.0, longitude = -76.0, altitude = 100.0,
        timestamp = 1_000L, icon = "ic_pin", color = 0,
    )

    @Test
    fun replaceAll_swapsTheWholeTable() = runBlocking {
        repo.insertAll(listOf(coord("a"), coord("b")))
        repo.replaceAll(listOf(coord("c")))
        assertEquals(listOf("c"), repo.getAllCoordinatesList().map { it.id })
    }

    @Test
    fun replaceAll_failingInsert_keepsOriginalRows() = runBlocking {
        repo.insertAll(listOf(coord("a"), coord("b")))
        // Make one row of the new set fail mid-insert.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER fail_boom BEFORE INSERT ON coordinates WHEN NEW.id = 'boom' " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END"
        )
        val result = runCatching { repo.replaceAll(listOf(coord("c"), coord("boom"), coord("d"))) }
        assertTrue(result.isFailure)
        assertEquals(setOf("a", "b"), repo.getAllCoordinatesList().map { it.id }.toSet())
    }
}
