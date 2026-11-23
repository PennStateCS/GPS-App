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
        assertEquals(1, repo.replaceAllCount)                // one atomic replace, not delete + insert
    }

    @Test fun replace_failedWrite_keepsExistingPoints() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep"), coord("b", "Keep too")))
        repo.failReplace = IllegalStateException("disk full")
        val result = runCatching { useCase(repo)(backupOf(coord("c", "New")), replace = true) }
        assertTrue(result.isFailure)
        assertEquals(listOf("Keep", "Keep too"), repo.store.map { it.name })
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

    /** One valid row and two invalid ones (null island, malformed). */
    private val mostlyBad = """
        [
          {"id":"good","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":10.0,"timestamp":1},
          {"id":"bad","name":"NullIsland","latitude":0.0,"longitude":0.0},
          5
        ]
    """.trimIndent()

    @Test fun partialReplace_withoutConfirmation_keepsEverything() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep"), coord("b", "Keep too")))
        val plan = useCase(repo)(mostlyBad, replace = true)          // default answer: cancel
        assertTrue(plan.cancelled)
        assertEquals(0, repo.replaceAllCount)
        assertEquals(listOf("Keep", "Keep too"), repo.store.map { it.name })
        assertEquals("Import canceled: existing coordinates kept", plan.summaryMessage())
    }

    @Test fun partialReplace_isAskedWithTheCounts() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a"), coord("b"), coord("c")))
        var asked: Triple<Int, Int, Int>? = null
        useCase(repo)(mostlyBad, replace = true) { p, existing ->
            asked = Triple(p.insertCount, p.skippedCount, existing)
            ImportCoordinateBackupUseCase.PartialReplaceDecision.CANCEL
        }
        assertEquals(Triple(1, 2, 3), asked)
    }

    @Test fun partialReplace_confirmed_replaces() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a"), coord("b")))
        useCase(repo)(mostlyBad, replace = true) { _, _ -> ImportCoordinateBackupUseCase.PartialReplaceDecision.REPLACE }
        assertEquals(1, repo.replaceAllCount)
        assertEquals(listOf("Good"), repo.store.map { it.name })
    }

    @Test fun partialReplace_mergeInstead_keepsExistingPoints() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        val plan = useCase(repo)(mostlyBad, replace = true) { _, _ -> ImportCoordinateBackupUseCase.PartialReplaceDecision.MERGE_INSTEAD }
        assertEquals(0, repo.replaceAllCount)
        assertEquals(listOf("Keep", "Good"), repo.store.map { it.name })
        assertEquals(app.surrealar.data.backup.ImportMode.MERGE, plan.mode)
    }

    @Test fun cleanReplace_isNotAskedAgain() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a")))
        var asked = false
        useCase(repo)(backupOf(coord("c", "Only")), replace = true) { _, _ -> asked = true; ImportCoordinateBackupUseCase.PartialReplaceDecision.CANCEL }
        assertTrue(!asked)
        assertEquals(listOf("Only"), repo.store.map { it.name })
    }

    @Test fun legacyJsonArray_malformedRowCountsAsSkipped() = runTest {
        val repo = FakeCoordinateRepository()
        // The second entry is not an object: getJSONObject throws, so the row is skipped and counted.
        val raw = """[{"id":"good","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":10.0,"timestamp":1}, 5]"""
        val plan = useCase(repo)(raw, replace = false)
        assertEquals(listOf("Good"), repo.store.map { it.name })
        assertEquals(1, plan.skippedCount)
    }
}
