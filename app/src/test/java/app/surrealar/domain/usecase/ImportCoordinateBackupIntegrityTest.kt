package app.surrealar.domain.usecase

import app.surrealar.data.backup.ImportMode
import app.surrealar.data.export.CoordinateBackup
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.FileType
import app.surrealar.domain.model.Model
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Data-integrity behaviour of [ImportCoordinateBackupUseCase] beyond the basic replace/merge cases in
 * ImportCoordinateBackupUseCaseTest: exact field preservation into the repository, rejection of damaged
 * input without touching existing points, and the legacy-array mapping.
 */
// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportCoordinateBackupIntegrityTest {

    private fun coord(id: String, name: String = "P $id", lat: Double = 41.0, modelId: String? = null) = Coordinate(
        id = id, name = name, latitude = lat, longitude = -76.0, altitude = 100.0,
        timestamp = 1_000L, icon = "ic_pin", color = 0, modelId = modelId,
    )

    private fun surveyed(id: String = "s1") = Coordinate(
        id = id, name = "Monument $id", latitude = 41.246813579246813, longitude = -75.88112233445566,
        altitude = 187.12345678901234, timestamp = 1_763_900_000_123L, icon = "model:m1", color = -16_776_961,
        provider = "RS2_TCP", rtkStatus = "FIX", satsUsed = 24, satsVisible = 31, hdop = 0.6,
        horizontalAccuracyM = 0.011, verticalAccuracyM = 0.019, correctionSource = "NTRIP", correctionAgeS = 1.0,
        altitudeMsl = 219.4567, geoidSeparationM = -32.3332, antennaHeightM = 2.0, crsEpsg = 4326,
        captureMethod = "AVERAGED", averagedSamples = 300, averageDurationMs = 300_000L,
        stdLatM = 0.003, stdLonM = 0.004, stdAltM = 0.008, sourceDevice = "Emlid Reach RS2+",
        modelId = "m1", renderEnabled = true, createdAt = 10L, updatedAt = 20L,
        modelScale = 1.5, modelYawDeg = 33.0, modelVerticalOffsetM = -0.4,
        modelPlacementOrigin = "BOTTOM_CENTER", modelMoveEastM = 0.25, modelMoveNorthM = -0.125, modelMoveUpM = 0.0,
    )

    private fun localModel(id: String) = Model(
        id = id, name = "M", fileName = "$id.glb", filePath = "/$id.glb", fileSize = 1L, dateAdded = 0L,
        fileType = FileType.MESH_MODEL,
    )

    private fun backupOf(vararg coords: Coordinate) =
        CoordinateBackup.export(coords.toList(), models = emptyList(), appVersion = "1.0")

    private fun useCase(repo: FakeCoordinateRepository, models: FakeModelRepository = FakeModelRepository()) =
        ImportCoordinateBackupUseCase(repo, models, ValidateCoordinateForSaveUseCase())

    @Test fun surveyedPoint_isStoredExactlyAsBackedUp() = runTest {
        val repo = FakeCoordinateRepository()
        val original = surveyed()
        val plan = useCase(repo, FakeModelRepository(listOf(localModel("m1"))))(backupOf(original), replace = false)
        assertEquals(listOf(original), repo.store)
        assertTrue(plan.missingModelRefs.isEmpty())   // the model is installed locally
        assertEquals("Imported 1 · merged", plan.summaryMessage())
    }

    @Test fun missingModel_isJudgedAgainstLocalModels_notTheBackupList() = runTest {
        val repo = FakeCoordinateRepository()
        // The backup itself lists model m1, but it is not installed on this device.
        val raw = CoordinateBackup.export(listOf(surveyed()), listOf(localModel("m1")), null)
        val plan = useCase(repo)(raw, replace = false)
        assertEquals(1, plan.insertCount)
        assertEquals(listOf("'Monument s1' (s1) → m1"), plan.missingModelRefs)
        assertEquals("Imported 1 · 1 missing model(s) · merged", plan.summaryMessage())
    }

    @Test fun fullBackup_withNonPositiveModelScale_neverReachesTheRepository() = runTest {
        val repo = FakeCoordinateRepository()
        val raw = backupOf(surveyed("bad").copy(modelScale = 0.0), surveyed("good"))
        val plan = useCase(repo, FakeModelRepository(listOf(localModel("m1"))))(raw, replace = false)
        assertEquals(listOf("good"), repo.store.map { it.id })
        assertEquals(1, plan.skippedInvalid.size)
        assertTrue(plan.skippedInvalid.single(), "Model scale must be a positive number" in plan.skippedInvalid.single())
    }

    @Test fun fullBackup_pointWithNoRecordedCrs_isRestoredWithNoCrs() = runTest {
        val repo = FakeCoordinateRepository()
        val original = surveyed().copy(crsEpsg = null)
        useCase(repo, FakeModelRepository(listOf(localModel("m1"))))(backupOf(original), replace = false)
        assertEquals(listOf(original), repo.store)
    }

    @Test fun garbageInput_throws_andLeavesExistingDataIntact() = runTest {
        for (replace in listOf(true, false)) {
            val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
            val result = runCatching { useCase(repo)("this is not a backup", replace = replace) }
            assertTrue(result.isFailure)
            assertEquals(listOf("Keep"), repo.store.map { it.name })
            assertEquals(0, repo.replaceAllCount)
            assertEquals(0, repo.deleteAllCount)
        }
    }

    @Test fun truncatedFullBackup_throws_andLeavesExistingDataIntact() = runTest {
        val raw = backupOf(surveyed("x"), surveyed("y"), surveyed("z"))
        val truncated = raw.substring(0, raw.length * 2 / 3)
        for (replace in listOf(true, false)) {
            val repo = FakeCoordinateRepository(listOf(coord("a", "Keep"), coord("b", "Keep too")))
            val result = runCatching { useCase(repo)(truncated, replace = replace) }
            assertTrue(result.isFailure)
            assertEquals(listOf("Keep", "Keep too"), repo.store.map { it.name })
            assertEquals(0, repo.replaceAllCount)
        }
    }

    @Test fun replaceWithNoValidRows_isANoOp_andNeverWipes() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        var asked = false
        val raw = """{"format":"surreal-coordinate-backup","schemaVersion":1,"coordinates":[
            {"id":"n","name":"Null island","latitude":0.0,"longitude":0.0,"altitude":1.0}
        ]}"""
        val plan = useCase(repo)(raw, replace = true) { _, _ -> asked = true; ImportCoordinateBackupUseCase.PartialReplaceDecision.REPLACE }
        assertFalse(asked)
        assertTrue(plan.isNoOp)
        assertEquals(1, plan.skippedCount)
        assertEquals(0, repo.replaceAllCount)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
        assertEquals("No coordinates to import", plan.summaryMessage())
    }

    @Test fun emptyBackup_inMergeMode_writesNothing() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        val plan = useCase(repo)(backupOf(), replace = false)
        assertTrue(plan.isNoOp)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
    }

    @Test fun partialReplace_onEmptyDevice_isNotAsked_andImportsTheValidRows() = runTest {
        val repo = FakeCoordinateRepository()
        var asked = false
        val raw = """{"format":"surreal-coordinate-backup","coordinates":[
            {"id":"ok","name":"Ok","latitude":41.0,"longitude":-76.0,"altitude":1.0},
            {"id":"bad","name":"Bad","latitude":41.0,"longitude":-76.0}
        ]}"""
        val plan = useCase(repo)(raw, replace = true) { _, _ -> asked = true; ImportCoordinateBackupUseCase.PartialReplaceDecision.CANCEL }
        assertFalse(asked)   // nothing local to lose
        assertEquals(ImportMode.REPLACE, plan.mode)
        assertEquals(listOf("ok"), repo.store.map { it.id })
        assertEquals("Imported 1 · 1 skipped · replaced", plan.summaryMessage())
    }

    @Test fun mergeInstead_reportsTheOverwrittenDuplicates() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("ok", "Old"), coord("other", "Other")))
        val raw = """{"format":"surreal-coordinate-backup","coordinates":[
            {"id":"ok","name":"New","latitude":41.0,"longitude":-76.0,"altitude":1.0},
            {"id":"bad","name":"Bad","latitude":41.0,"longitude":-76.0}
        ]}"""
        val plan = useCase(repo)(raw, replace = true) { _, _ -> ImportCoordinateBackupUseCase.PartialReplaceDecision.MERGE_INSTEAD }
        assertEquals(ImportMode.MERGE, plan.mode)
        assertEquals(listOf("ok"), plan.duplicateIds)
        assertEquals(setOf("New", "Other"), repo.store.map { it.name }.toSet())
        assertEquals("Imported 1 · 1 skipped · 1 overwritten · merged", plan.summaryMessage())
    }

    @Test fun replaceCancelled_reportsCancelled_andKeepsEverything() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        val raw = """{"format":"surreal-coordinate-backup","coordinates":[
            {"id":"ok","name":"Ok","latitude":41.0,"longitude":-76.0,"altitude":1.0},
            {"id":"bad","name":"Bad","latitude":91.0,"longitude":-76.0,"altitude":1.0}
        ]}"""
        val plan = useCase(repo)(raw, replace = true) { _, existing ->
            assertEquals(1, existing)
            ImportCoordinateBackupUseCase.PartialReplaceDecision.CANCEL
        }
        assertTrue(plan.cancelled)
        assertTrue(plan.isNoOp)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
    }

    // ── legacy basic JSON array ─────────────────────────────────────────────────

    @Test fun legacyArray_mapsOldMenuIcons_andKeepsOtherIcons() = runTest {
        val repo = FakeCoordinateRepository()
        val raw = """[
            {"id":"a","name":"A","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"icon":"ic_menu_camera"},
            {"id":"b","name":"B","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"icon":"ic_menu_gallery"},
            {"id":"c","name":"C","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"icon":"ic_menu_slideshow"},
            {"id":"d","name":"D","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"icon":"model:m9"},
            {"id":"e","name":"E","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1}
        ]"""
        useCase(repo)(raw, replace = false)
        assertEquals(listOf("ic_pin", "ic_star", "ic_home", "model:m9", "ic_pin"), repo.store.map { it.icon })
    }

    @Test fun legacyArray_keepsBasicFieldsExactly_andFillsDefaults() = runTest {
        val repo = FakeCoordinateRepository()
        val raw = """[
            {"id":"a","name":"Alpha","latitude":41.246813579246813,"longitude":-75.88112233445566,
             "altitude":187.12345678901234,"timestamp":1763900000123,"icon":"ic_star","color":-65536},
            {"latitude":41.5,"longitude":-75.5,"altitude":2.5,"timestamp":7}
        ]"""
        val plan = useCase(repo)(raw, replace = false)
        assertEquals(2, plan.insertCount)
        val a = repo.store[0]
        assertEquals(41.246813579246813, a.latitude, 0.0)
        assertEquals(-75.88112233445566, a.longitude, 0.0)
        assertEquals(187.12345678901234, a.altitude, 0.0)
        assertEquals(1_763_900_000_123L, a.timestamp)
        assertEquals("ic_star", a.icon)
        assertEquals(-65536, a.color)
        assertEquals("fused", a.provider)

        val b = repo.store[1]
        assertTrue(b.id.isNotBlank())          // generated id
        assertEquals(b.id, b.name)             // name falls back to the id
        assertEquals(0xFF64B5F6.toInt(), b.color)
    }

    @Test fun legacyArray_invalidRowsAreLabelled_andValidOnesImported() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("keep", "Keep")))
        val raw = """[
            {"id":"n","name":"NoLat","longitude":-76.0,"altitude":1.0},
            {"id":"o","name":"OutOfRange","latitude":41.0,"longitude":-200.0,"altitude":1.0},
            "a string row",
            {"id":"g","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":3}
        ]"""
        val plan = useCase(repo)(raw, replace = false)
        assertEquals(listOf("Keep", "Good"), repo.store.map { it.name })
        assertEquals(listOf("row 3 (malformed)", "'NoLat' (n)", "'OutOfRange' (o)"), plan.skippedInvalid)
    }

    @Test fun legacyArray_rowWithoutAltitude_isSkippedAndReported_notImportedAtZero() = runTest {
        val repo = FakeCoordinateRepository(emptyList())
        val raw = """[
            {"id":"m","name":"Missing","latitude":41.0,"longitude":-76.0,"timestamp":1},
            {"id":"n","name":"NullAlt","latitude":41.0,"longitude":-76.0,"altitude":null,"timestamp":1},
            {"id":"s","name":"TextAlt","latitude":41.0,"longitude":-76.0,"altitude":"high","timestamp":1},
            {"id":"z","name":"Zero","latitude":41.0,"longitude":-76.0,"altitude":0.0,"timestamp":1},
            {"id":"g","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":187.25,"timestamp":1}
        ]"""
        val plan = useCase(repo)(raw, replace = false)
        // 0.0 is a legitimate height and is kept exactly; missing / null / non-numeric never become 0.
        assertEquals(listOf("Zero", "Good"), repo.store.map { it.name })
        assertEquals(0.0, repo.store[0].altitude, 0.0)
        assertEquals(187.25, repo.store[1].altitude, 0.0)
        assertEquals(
            listOf(
                "'Missing' (m): no altitude in backup",
                "'NullAlt' (n): no altitude in backup",
                "'TextAlt' (s): no altitude in backup",
            ),
            plan.skippedInvalid,
        )
    }

    @Test fun legacyArray_replaceWithRowsMissingAltitude_asksBeforeWiping() = runTest {
        // A skipped row makes the replace "partial", so the default (CANCEL) keeps the local points.
        val repo = FakeCoordinateRepository(listOf(coord("keep", "Keep")))
        val raw = """[
            {"id":"m","name":"Missing","latitude":41.0,"longitude":-76.0,"timestamp":1},
            {"id":"g","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1}
        ]"""
        val plan = useCase(repo)(raw, replace = true)
        assertTrue(plan.cancelled)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
    }

    @Test fun legacyEmptyArray_isANoOp() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("keep", "Keep")))
        val plan = useCase(repo)("[]", replace = true)
        assertTrue(plan.isNoOp)
        assertEquals(0, repo.replaceAllCount)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
    }
}
