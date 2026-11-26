package app.surrealar.data.export

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.Model
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.domain.usecase.FakeModelRepository
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase.PartialReplaceDecision
import app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stabilization S2 item 10: newer schema versions are rejected before anything is written, malformed entries are
 * counted and listed (never dropped silently) and shown before committing, older backups still import, and an
 * invalid or declined backup never deletes existing points.
 */
// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateBackupCompatibilityTest {

    private fun coord(id: String, name: String = id, modelId: String? = null) = Coordinate(
        id = id, name = name, latitude = 41.0, longitude = -76.0, altitude = 100.0,
        timestamp = 1_000L, icon = "ic_pin", color = 0, modelId = modelId,
    )

    private fun useCase(repo: FakeCoordinateRepository, models: List<Model> = emptyList()) =
        ImportCoordinateBackupUseCase(repo, FakeModelRepository(models), ValidateCoordinateForSaveUseCase())

    private fun backup(schema: String, coordinates: String = """[{"id":"n","name":"New","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"color":0,"icon":""}]""") =
        """{"format":"surreal-coordinate-backup","schemaVersion":$schema,"coordinates":$coordinates}"""

    @Test fun `a newer schema version is rejected with a clear message`() {
        try {
            CoordinateBackup.parse(backup("2"))
            fail("schema 2 must be rejected")
        } catch (e: CoordinateBackup.UnsupportedBackupException) {
            assertTrue(e.message!!, e.message!!.contains("newer version") && e.message!!.contains("format 2") &&
                e.message!!.contains("Nothing was changed"))
        }
    }

    @Test fun `unrecognised schema versions are rejected, absent and current ones accepted`() {
        for (bad in listOf("0", "-1", "1.5", "\"abc\"", "99")) {
            assertTrue(bad, runCatching { CoordinateBackup.parse(backup(bad)) }.exceptionOrNull() is CoordinateBackup.UnsupportedBackupException)
        }
        assertEquals(1, CoordinateBackup.parse(backup("1")).schemaVersion)
        val noVersion = """{"format":"surreal-coordinate-backup","coordinates":[]}"""
        assertEquals(1, CoordinateBackup.parse(noVersion).schemaVersion)
    }

    @Test fun `a newer schema in replace mode never touches existing points`() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        val r = runCatching { useCase(repo)(backup("2"), replace = true) { _, _ -> PartialReplaceDecision.REPLACE } }
        assertTrue(r.exceptionOrNull() is CoordinateBackup.UnsupportedBackupException)
        assertEquals(0, repo.replaceAllCount)
        assertEquals(0, repo.deleteAllCount)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
    }

    @Test fun `a backup without a coordinate list is damaged, not empty - replace never wipes`() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        val r = runCatching { useCase(repo)("""{"format":"surreal-coordinate-backup","schemaVersion":1}""", replace = true) }
        assertTrue(r.exceptionOrNull() is CoordinateBackup.UnsupportedBackupException)
        val notArray = runCatching { useCase(repo)(backup("1", coordinates = "{}"), replace = true) }
        assertTrue(notArray.exceptionOrNull() is CoordinateBackup.UnsupportedBackupException)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
    }

    @Test fun `non-object entries are counted and listed, not silently dropped`() {
        // Before the fix entries 1 and 3 vanished: skippedInvalid was empty and the import looked complete.
        val r = CoordinateBackup.parse(backup("1", coordinates =
            """[{"id":"g","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"color":0,"icon":""}, null, 5, "x"]"""))
        assertEquals(listOf("g"), r.coordinates.map { it.id })
        assertEquals(listOf("entry #1: not a coordinate object", "entry #2: not a coordinate object",
            "entry #3: not a coordinate object"), r.skippedInvalid)
    }

    private val partial = backup("1", coordinates = """[
        {"id":"g","name":"Good","latitude":41.0,"longitude":-76.0,"altitude":1.0,"timestamp":1,"color":0,"icon":""},
        {"id":"z","name":"NoAlt","latitude":41.0,"longitude":-76.0,"timestamp":1},
        7]""")

    @Test fun `a partial merge is shown before committing - declining writes nothing`() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        var shown: List<String>? = null
        val plan = useCase(repo)(partial, replace = false, confirmPartialImport = { p -> shown = p.skippedInvalid; false })
        assertEquals(listOf("'NoAlt' (z): no altitude in backup", "entry #2: not a coordinate object"), shown)
        assertTrue(plan.cancelled)
        assertEquals(listOf("Keep"), repo.store.map { it.name })
        assertEquals("Import canceled: existing coordinates kept", plan.summaryMessage())
    }

    @Test fun `a partial merge confirmed imports the valid subset`() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        val plan = useCase(repo)(partial, replace = false, confirmPartialImport = { true })
        assertEquals(listOf("Keep", "Good"), repo.store.map { it.name })
        assertEquals("Imported 1 · 2 skipped · merged", plan.summaryMessage())
    }

    @Test fun `a partial replace with local points asks once (replace dialog), never twice`() = runTest {
        val repo = FakeCoordinateRepository(listOf(coord("a", "Keep")))
        var partialAsked = false
        useCase(repo)(partial, replace = true, confirmPartialReplace = { _, _ -> PartialReplaceDecision.MERGE_INSTEAD },
            confirmPartialImport = { partialAsked = true; true })
        assertFalse(partialAsked)
        assertEquals(listOf("Keep", "Good"), repo.store.map { it.name })
    }

    @Test fun `a clean import is not asked`() = runTest {
        val repo = FakeCoordinateRepository()
        var asked = false
        useCase(repo)(backup("1"), replace = false, confirmPartialImport = { asked = true; false })
        assertFalse(asked)
        assertEquals(listOf("New"), repo.store.map { it.name })
    }

    @Test fun `skipped preview lists at most five entries`() {
        val plan = app.surrealar.data.backup.BackupImportPlan(app.surrealar.data.backup.ImportMode.MERGE,
            listOf(coord("g")), List(7) { "bad $it" }, emptyList(), emptyList())
        assertEquals("• bad 0\n• bad 1\n• bad 2\n• bad 3\n• bad 4\n… and 2 more", plan.skippedPreview())
        assertEquals("", plan.copy(skippedInvalid = emptyList()).skippedPreview())
    }

    @Test fun `an older backup (no provenance, no position reference) still imports with model link and metadata`() = runTest {
        val old = """{"format":"surreal-coordinate-backup","schemaVersion":1,"appVersion":"1.0","models":[{"id":"m1","name":"Barn"}],
            "coordinates":[{"id":"old","name":"Old","note":"iron pin","latitude":41.1,"longitude":-75.9,"altitude":180.5,
            "timestamp":1700000000000,"color":5,"provider":"RS2_TCP","crsEpsg":6319,"icon":"ic_pin","modelId":"m1",
            "modelScale":2.0,"modelYawDeg":45.0,"horizontalAccuracyM":0.012,"rtkStatus":"FIX"}]}"""
        val repo = FakeCoordinateRepository()
        val plan = useCase(repo, models = listOf(Model(id = "m1", name = "Barn", fileName = "barn.glb", filePath = "models/barn.glb", fileSize = 1L, dateAdded = 0L)))(old, replace = false)
        assertTrue(plan.skippedInvalid.isEmpty())
        assertTrue(plan.missingModelRefs.isEmpty())
        val c = repo.store.single()
        assertEquals("m1", c.modelId)
        assertEquals(2.0, c.modelScale!!, 0.0)
        assertEquals(45.0, c.modelYawDeg!!, 0.0)
        assertEquals("iron pin", c.note)
        assertEquals("RS2_TCP", c.provider)
        assertEquals(6319, c.crsEpsg)
        assertEquals(0.012, c.horizontalAccuracyM!!, 0.0)
        assertNull(c.coordinateEpoch)
        assertNull(c.sourceFrame)
    }

    @Test fun `round trip keeps model association, provenance, epoch and metadata`() {
        val c = coord("p", "P1", modelId = "m1").copy(note = "n", provider = "EXTERNAL_TCP",
            captureMethod = "EXTERNAL_GNSS_REFERENCE_UNCONFIRMED", coordinateEpoch = 2026.5, sourceFrame = "wgs84-as-itrf2014",
            receiverFrame = "nad83-2011", orthometricHeightSource = "RECEIVER_GGA", orthometricModel = "GEOID18",
            modelScale = 1.5, modelPlacementOrigin = "BOTTOM_CENTER", sourceDevice = "Reach RS4", crsEpsg = 4979)
        val back = CoordinateBackup.parse(CoordinateBackup.export(listOf(c), emptyList(), "x")).coordinates.single()
        assertEquals(c, back)
    }
}
