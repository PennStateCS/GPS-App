package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.ui.viewpoints.CoordinatesViewModel.SaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Saves report their outcome, and a started write survives the screen going away. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatesViewModelSaveTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** The ViewModel reads allCoordinates when built; the shared fake doesn't support it. */
    private fun withList(r: CoordinateRepository): CoordinateRepository = object : CoordinateRepository by r {
        override val allCoordinates: androidx.lifecycle.LiveData<List<Coordinate>> = androidx.lifecycle.MutableLiveData(emptyList())
    }

    private fun point(id: String = "p1", lat: Double = 41.3) = Coordinate(
        id = id, name = "P1", latitude = lat, longitude = -76.0, altitude = 300.0,
        timestamp = 0L, icon = "", color = 0,
    )

    @Test fun `a successful save reports Saved`() {
        val repo = FakeCoordinateRepository()
        var result: SaveResult? = null
        CoordinatesViewModel(withList(repo)).addCoordinate(point()) { result = it }
        assertEquals(SaveResult.Saved, result)
        assertNull(result!!.userMessage)
        assertEquals(1, repo.store.size)
    }

    @Test fun `an invalid point is rejected with its reasons and not written`() {
        val repo = FakeCoordinateRepository()
        var result: SaveResult? = null
        CoordinatesViewModel(withList(repo)).addCoordinate(point(lat = 0.0).copy(longitude = 0.0)) { result = it }
        assertTrue(result is SaveResult.Rejected)
        assertTrue(result!!.userMessage!!.startsWith("Not saved:"))
        assertTrue(repo.store.isEmpty())
    }

    @Test fun `a database error reports Failed`() {
        val repo = object : app.surrealar.domain.repository.CoordinateRepository by FakeCoordinateRepository() {
            override suspend fun update(coordinate: Coordinate) { throw IllegalStateException("disk full") }
        }
        var result: SaveResult? = null
        CoordinatesViewModel(withList(repo)).updateCoordinate(point()) { result = it }
        assertTrue(result is SaveResult.Failed)
        assertEquals("Not saved: database error (IllegalStateException)", result!!.userMessage)
    }

    @Test fun `a write already started finishes when the screen goes away`() {
        val gate = CompletableDeferred<Unit>()
        val backing = FakeCoordinateRepository()
        val repo = object : app.surrealar.domain.repository.CoordinateRepository by backing {
            override suspend fun update(coordinate: Coordinate) { gate.await(); backing.update(coordinate) }
        }
        var called = false
        val job = CoordinatesViewModel(withList(repo)).updateCoordinate(point()) { called = true }
        job.cancel()                 // as when the fragment-scoped ViewModel is cleared (Back)
        gate.complete(Unit)
        assertEquals("the edit is still written", 1, backing.store.size)
        assertTrue("no UI callback after the screen is gone", !called)
    }
}
