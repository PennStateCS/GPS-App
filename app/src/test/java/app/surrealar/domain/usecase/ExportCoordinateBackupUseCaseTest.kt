package app.surrealar.domain.usecase

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.FileType
import app.surrealar.domain.model.Model
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportCoordinateBackupUseCaseTest {

    private val coord = Coordinate(
        id = "c1", name = "Point A", latitude = 41.0, longitude = -76.0, altitude = 100.0,
        timestamp = 1_000L, icon = "ic_pin", color = 0,
    )
    private val model = Model(
        id = "m1", name = "Barn", fileName = "barn.glb", filePath = "/models/barn.glb",
        fileSize = 10L, dateAdded = 0L, fileType = FileType.MESH_MODEL,
    )

    @Test fun export_includesCoordinatesAndModelMetadata() = runTest {
        val useCase = ExportCoordinateBackupUseCase(
            FakeCoordinateRepository(listOf(coord)),
            FakeModelRepository(listOf(model)),
        )
        val result = useCase("1.0-test")

        assertEquals(1, result.coordinateCount)
        assertEquals(1, result.modelCount)
        // The full backup is tagged and carries both the coordinate and the model metadata.
        assertTrue(result.json.contains("surreal-coordinate-backup"))
        assertTrue(result.json.contains("Point A"))
        assertTrue(result.json.contains("m1"))
        assertTrue(result.json.contains("barn.glb"))
    }

    @Test fun export_emptyData_stillProducesValidBackup() = runTest {
        val useCase = ExportCoordinateBackupUseCase(FakeCoordinateRepository(), FakeModelRepository())
        val result = useCase(null)
        assertEquals(0, result.coordinateCount)
        assertTrue(result.json.contains("surreal-coordinate-backup"))
    }
}
