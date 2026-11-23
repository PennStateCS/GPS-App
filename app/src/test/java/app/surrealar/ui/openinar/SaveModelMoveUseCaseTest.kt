package app.surrealar.ui.openinar

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.usecase.FakeCoordinateRepository
import io.github.pennstatecs.geokit.ar.model.ModelMove
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveModelMoveUseCaseTest {

    private fun point(east: Double? = null, yaw: Double? = null) = Coordinate(
        id = "p1", name = "P1", latitude = 41.3, longitude = -76.0, altitude = 300.0,
        timestamp = 0L, icon = "", color = 0, modelId = "m1", modelMoveEastM = east, modelYawDeg = yaw,
        altitudeMsl = 333.3, easting = 400_000.0, northing = 4_500_000.0, horizontalAccuracyM = 0.012,
    )

    @Test fun `a move adds to the saved offset and never moves the point`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(east = 1.0)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(eastM = 0.5f, northM = -2f, upM = 0.25f), fallbackYawDeg = 0f, nowMs = 9L)
        val c = repo.store.single()
        assertEquals(1.5, c.modelMoveEastM!!, 1e-9)
        assertEquals(-2.0, c.modelMoveNorthM!!, 1e-9)
        assertEquals(0.25, c.modelMoveUpM!!, 1e-9)
        assertEquals(41.3, c.latitude, 0.0); assertEquals(-76.0, c.longitude, 0.0); assertEquals(300.0, c.altitude, 0.0)
        assertEquals(9L, c.updatedAt)
        assertNull("yaw untouched without a twist", c.modelYawDeg)
        assertEquals("one read-modify-write", 1, repo.updateByIdCount)
    }

    @Test fun `surveyed values are identical after a save`() = runTest {
        val before = point(east = 0.3, yaw = 10.0)
        val repo = FakeCoordinateRepository(listOf(before))
        val saved = SaveModelMoveUseCase(repo)("p1", ModelMove(1f, 2f, 3f, 45f), fallbackYawDeg = 0f, nowMs = 5L)
        for (c in listOf(saved, repo.store.single())) {
            // Bit-for-bit: the point's position and survey metadata are copied, never recomputed.
            assertEquals(before.latitude.toRawBits(), c.latitude.toRawBits())
            assertEquals(before.longitude.toRawBits(), c.longitude.toRawBits())
            assertEquals(before.altitude.toRawBits(), c.altitude.toRawBits())
            // Everything but the model offsets / yaw and updatedAt is unchanged.
            assertEquals(before, c.copy(modelMoveEastM = before.modelMoveEastM, modelMoveNorthM = null,
                modelMoveUpM = null, modelYawDeg = before.modelYawDeg, updatedAt = before.updatedAt))
        }
    }

    @Test fun `a reset back to the surveyed point stores no offset`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(east = 1.25)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(eastM = -1.25f), fallbackYawDeg = 0f)
        assertNull(repo.store.single().modelMoveEastM)
    }

    @Test fun `a twist is saved relative to the yaw the model showed`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(yaw = null)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(yawDeg = -30f), fallbackYawDeg = 170f)
        assertEquals(140.0, repo.store.single().modelYawDeg!!, 1e-9)
        SaveModelMoveUseCase(repo)("p1", ModelMove(yawDeg = 50f), fallbackYawDeg = 140f)
        assertEquals(-170.0, repo.store.single().modelYawDeg!!, 1e-9)   // wrapped
    }

    @Test fun `a twist adds to the point's stored yaw, not a stale fallback`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(yaw = 20.0)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(yawDeg = 5f), fallbackYawDeg = 999f)
        assertEquals(25.0, repo.store.single().modelYawDeg!!, 1e-9)
    }

    @Test fun `two saves both count`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point()))
        val save = SaveModelMoveUseCase(repo)
        save("p1", ModelMove(eastM = 0.4f), 0f)
        save("p1", ModelMove(eastM = 0.6f), 0f)
        assertEquals(1.0, repo.store.single().modelMoveEastM!!, 1e-9)
    }

    @Test fun `a failed save leaves the stored point unchanged`() = runTest {
        val before = point(east = 0.7)
        val repo = FakeCoordinateRepository(listOf(before)).apply { failUpdate = IllegalStateException("disk full") }
        val r = runCatching { SaveModelMoveUseCase(repo)("p1", ModelMove(eastM = 1f), 0f) }
        assertTrue(r.isFailure)
        assertEquals(before, repo.store.single())
    }

    @Test fun `a missing point fails without writing`() = runTest {
        val repo = FakeCoordinateRepository()
        assertTrue(runCatching { SaveModelMoveUseCase(repo)("nope", ModelMove(eastM = 1f), 0f) }.isFailure)
        assertTrue(repo.store.isEmpty())
    }

    @Test fun `float noise is rounded to millimetres`() {
        assertEquals(0.3, SaveModelMoveUseCase.total(0.1, 0.2f)!!, 0.0)
        assertNull(SaveModelMoveUseCase.total(null, 0.0004f))
    }
}
