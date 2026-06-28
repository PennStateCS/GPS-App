package app.surrealar.domain.usecase

import app.surrealar.data.export.CoordinateBackup
import app.surrealar.domain.model.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json (CoordinateBackup + the legacy array parser) is stubbed in plain JVM unit tests;
// Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportCoordinateBackupUseCaseTest {

    private fun coord(id: String, name: String = "P", lat: Double = 41.0, lon: Double = -76.0, modelId: String? = null) =
        Coordinate(
            id = id, name = name, latitude = lat, longitude = lon, altitude = 100.0,
            timestamp = 1_000L, icon = "ic_pin", color = 0, modelId = modelId,
        )

    private fun backupOf(vararg coords: Coordinate) =
        CoordinateBackup.export(coords.toList(), models = emptyList(), appVersion = "1.0")

    private fun useCase(repo: FakeCoordinateRepository, models: FakeModelRepository = FakeModelRepository()) =
        ImportCoordinateBackupUseCase(repo, models, ValidateCoordinateForSaveUseCase())

    @Test fun fullBackup_insertsCoordinates() = runTest {
        val repo = FakeCoordinateRepository()
        val plan = useCase(repo)(backupOf(coord("a", "Alpha")), replace = false)
        assertEquals(1, plan.insertCount)
        assertEquals(listOf("Alpha"), repo.store.map { it.name })
    }

    @Test fun merge_overwritesDuplicateId_andReportsIt() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Old")))
        val plan = useCase(repo)(backupOf(coord("a", "New")), replace = false)
        assertEquals(0, repo.deleteAllCount)                 // merge never clears
        assertEquals(listOf("New"), repo.store.map { it.name }) // same id overwritten
        assertTrue("a" in plan.duplicateIds)
    }

    @Test fun replace_clearsExistingFirst() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a"), coord("b")))
        val plan = useCase(repo)(backupOf(coord("c", "Only")), replace = true)
        assertEquals(1, repo.deleteAllCount)
        assertEquals(listOf("Only"), repo.store.map { it.name })
        assertTrue(plan.duplicateIds.isEmpty())              // replace doesn't report duplicates
    }

    @Test fun missingModelReference_isReported_notFatal() = runTest {
        val repo = FakeCoordinateRepository()
        // No local models, but the imported coordinate links model "ghost".
        val plan = useCase(repo)(backupOf(coord("a", "Linked", modelId = "ghost")), replace = false)
        assertEquals(1, plan.insertCount)                    // still imported
        assertTrue(plan.missingModelRefs.any { it.contains("ghost") })
    }

    @Test fun legacyJsonArray_validatesAndSkipsInvalid() = runTest {
        val repo = FakeCoordinateRepository()
        val raw = """
            [
              {"id":"good","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":10.0,"timestamp":1},
              {"id":"bad","name":"NullIsland","latitude":0.0,"longitude":0.0}
            ]
        """.trimIndent()
        val plan = useCase(repo)(raw, replace = false)
        assertEquals(listOf("Good"), repo.store.map { it.name })
        assertTrue(plan.skippedInvalid.any { it.contains("bad") })
    }
}
