package app.surrealar.ui.openinar

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.usecase.FakeCoordinateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveModelMoveUseCaseTest {

    private fun point(east: Double? = null, yaw: Double? = null) = Coordinate(
        id = "p1", name = "P1", latitude = 41.3, longitude = -76.0, altitude = 300.0,
        timestamp = 0L, icon = "", color = 0, modelId = "m1", modelMoveEastM = east, modelYawDeg = yaw,
    )

    @Test fun `a move adds to the saved offset and never moves the point`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(east = 1.0)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(eastM = 0.5f, northM = -2f, upM = 0.25f), resolvedYawDeg = 0f, nowMs = 9L)
        val c = repo.store.single()
        assertEquals(1.5, c.modelMoveEastM!!, 1e-9)
        assertEquals(-2.0, c.modelMoveNorthM!!, 1e-9)
        assertEquals(0.25, c.modelMoveUpM!!, 1e-9)
        assertEquals(41.3, c.latitude, 0.0); assertEquals(-76.0, c.longitude, 0.0); assertEquals(300.0, c.altitude, 0.0)
        assertEquals(9L, c.updatedAt)
        assertNull("yaw untouched without a twist", c.modelYawDeg)
    }

    @Test fun `a reset back to the surveyed point stores no offset`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(east = 1.25)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(eastM = -1.25f), resolvedYawDeg = 0f)
        assertNull(repo.store.single().modelMoveEastM)
    }

    @Test fun `a twist is saved relative to the yaw the model showed`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point(yaw = null)))
        SaveModelMoveUseCase(repo)("p1", ModelMove(yawDeg = -30f), resolvedYawDeg = 170f)
        assertEquals(140.0, repo.store.single().modelYawDeg!!, 1e-9)
        SaveModelMoveUseCase(repo)("p1", ModelMove(yawDeg = 50f), resolvedYawDeg = 140f)
        assertEquals(-170.0, repo.store.single().modelYawDeg!!, 1e-9)   // wrapped
    }

    @Test fun `float noise is rounded to millimetres`() {
        assertEquals(0.3, SaveModelMoveUseCase.total(0.1, 0.2f)!!, 0.0)
        assertNull(SaveModelMoveUseCase.total(null, 0.0004f))
    }
}
