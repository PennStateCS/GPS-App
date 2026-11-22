package app.surrealar.ui.models.readiness

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.FileType
import app.surrealar.domain.model.Model
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.domain.usecase.FakeModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelReadinessRepositoryTest {

    private val model = Model(
        id = "m1", name = "Shed", fileName = "shed.glb", filePath = "/nowhere/shed.glb", fileSize = 10,
        dateAdded = 0, fileType = FileType.OTHER, embeddedLatitude = 40.0, embeddedLongitude = -75.0,
        isValid = false, validationErrors = listOf("bad"), units = "m", defaultScale = 2.0,
    )

    private fun coord(id: String, modelId: String? = "m1", icon: String = "", pitch: Double? = null) = Coordinate(
        id = id, name = "Point $id", latitude = 41.0, longitude = -76.0, altitude = 100.0, timestamp = 1,
        icon = icon, color = 0, modelId = modelId, rtkStatus = "FIX", horizontalAccuracyM = 0.02,
        modelPlacementOrigin = "CENTER", modelScale = 1.5, modelYawDeg = 10.0, modelPitchDeg = pitch,
        modelRollDeg = 3.0, modelVerticalOffsetM = 0.5, modelOriginOffsetXM = 1.0, modelOriginOffsetYM = 2.0,
        modelOriginOffsetZM = 3.0, updatedAt = 5,
    )

    private class CountingSource : GltfFactsSource {
        var reads = 0
        override fun read(file: File): GltfFacts {
            reads++
            return GltfFacts(
                fileSizeBytes = 10, bounds = null, triangleCount = 12, vertexCount = 8, meshCount = 1,
                textureCount = 0, maxTextureSidePx = null, estimatedTextureBytes = 0,
                extensionsRequired = emptyList(), extensionsUsed = emptyList(), missingResources = emptyList(),
            )
        }
    }

    private object EchoRules : ReadinessRules {
        override fun evaluate(facts: ModelFacts) =
            ReadinessReport(Verdict.READY, emptyList(), "links=${facts.links.size}")
    }

    private fun repo(coords: FakeCoordinateRepository, source: GltfFactsSource = CountingSource()) =
        ModelReadinessRepository(
            FakeModelRepository(listOf(model)), coords, source, EchoRules, Dispatchers.Unconfined, { 999L },
        )

    @Test
    fun factsMapModelRowAndLinkedPoints() = runBlocking {
        val coords = FakeCoordinateRepository(
            listOf(coord("1"), coord("2", modelId = null, icon = "model:m1"), coord("3", modelId = "other")),
        )
        val facts = repo(coords).facts("m1", loadedByRenderer = true)!!

        assertEquals("Shed", facts.modelName)
        assertEquals("shed.glb", facts.fileName)
        assertEquals(40.0, facts.embeddedLatDeg!!, 0.0)
        assertEquals(false, facts.isValid)
        assertEquals(listOf("bad"), facts.validationErrors)
        assertEquals("m", facts.units)
        assertEquals(2.0, facts.defaultScale!!, 0.0)
        assertEquals(true, facts.loadedByRenderer)
        assertEquals(12L, facts.gltf.triangleCount)
        // Linked through the column and through the legacy icon; the other model's point is left out.
        assertEquals(listOf("1", "2"), facts.links.map { it.coordinateId })

        val l = facts.links.first()
        assertEquals("Point 1", l.coordinateName)
        assertEquals("CENTER", l.placementOrigin)
        assertEquals(1.5, l.scale!!, 0.0)
        assertEquals(10.0, l.yawDeg!!, 0.0)
        assertEquals(3.0, l.rollDeg!!, 0.0)
        assertEquals(0.5, l.verticalOffsetM!!, 0.0)
        assertEquals(3.0, l.originOffsetZM!!, 0.0)
        assertEquals("FIX", l.rtkStatus)
        assertEquals(0.02, l.horizontalAccuracyM!!, 0.0)
    }

    @Test
    fun missingModelGivesNull() = runBlocking {
        assertNull(repo(FakeCoordinateRepository()).facts("nope"))
    }

    @Test
    fun fileFactsAreCached() = runBlocking {
        val source = CountingSource()
        val r = repo(FakeCoordinateRepository(listOf(coord("1"))), source)
        r.report("m1")
        r.report("m1")
        r.reports(listOf(model), emptyList())
        assertEquals(1, source.reads)
    }

    @Test
    fun bottomCenterFixTargetsOnePoint() = runBlocking {
        val coords = FakeCoordinateRepository(listOf(coord("1"), coord("2")))
        val names = repo(coords).applyFix(
            "m1", ReadinessCheck("placement", "t", CheckStatus.WARN, "d", fixAction = FixAction.USE_BOTTOM_CENTER, fixCoordinateId = "2"),
        )
        assertEquals(listOf("Point 2"), names)
        assertEquals("BOTTOM_CENTER", coords.getById("2")!!.modelPlacementOrigin)
        assertEquals(999L, coords.getById("2")!!.updatedAt)
        assertEquals("CENTER", coords.getById("1")!!.modelPlacementOrigin)
        assertEquals(5L, coords.getById("1")!!.updatedAt)
    }

    @Test
    fun fixWithoutPointAppliesToAllLinks() = runBlocking {
        val coords = FakeCoordinateRepository(listOf(coord("1"), coord("2"), coord("3", modelId = "other")))
        val names = repo(coords).applyFix(
            "m1", ReadinessCheck("placement", "t", CheckStatus.WARN, "d", fixAction = FixAction.USE_BOTTOM_CENTER),
        )
        assertEquals(listOf("Point 1", "Point 2"), names.sorted())
        assertEquals("CENTER", coords.getById("3")!!.modelPlacementOrigin)
    }

    @Test
    fun verticalOffsetFixSetsValue() = runBlocking {
        val coords = FakeCoordinateRepository(listOf(coord("1")))
        repo(coords).applyFix(
            "m1", ReadinessCheck("ground", "t", CheckStatus.WARN, "d",
                fixAction = FixAction.SET_VERTICAL_OFFSET, fixCoordinateId = "1", fixValue = -1.25),
        )
        assertEquals(-1.25, coords.getById("1")!!.modelVerticalOffsetM!!, 0.0)
    }

    @Test
    fun verticalOffsetFixWithoutValueDoesNothing() = runBlocking {
        val coords = FakeCoordinateRepository(listOf(coord("1")))
        val names = repo(coords).applyFix(
            "m1", ReadinessCheck("ground", "t", CheckStatus.WARN, "d",
                fixAction = FixAction.SET_VERTICAL_OFFSET, fixCoordinateId = "1"),
        )
        assertTrue(names.isEmpty())
        assertEquals(0.5, coords.getById("1")!!.modelVerticalOffsetM!!, 0.0)
    }

    @Test
    fun rotateFixTogglesPitch() = runBlocking {
        val coords = FakeCoordinateRepository(listOf(coord("1"), coord("2", pitch = -90.0)))
        repo(coords).applyFix(
            "m1", ReadinessCheck("upAxis", "t", CheckStatus.WARN, "d", fixAction = FixAction.ROTATE_UP_AXIS),
        )
        assertEquals(-90.0, coords.getById("1")!!.modelPitchDeg!!, 0.0)
        assertEquals(0.0, coords.getById("2")!!.modelPitchDeg!!, 0.0)
    }

    @Test
    fun linkPointAndNoneChangeNothing() = runBlocking {
        val coords = FakeCoordinateRepository(listOf(coord("1")))
        val r = repo(coords)
        assertTrue(r.applyFix("m1", ReadinessCheck("links", "t", CheckStatus.INFO, "d", fixAction = FixAction.LINK_POINT)).isEmpty())
        assertTrue(r.applyFix("m1", ReadinessCheck("x", "t", CheckStatus.PASS, "d")).isEmpty())
        assertEquals(5L, coords.getById("1")!!.updatedAt)
    }
}
