package app.surrealar.ui.openinar

import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrepareArCoordinateModelsUseCaseTest {

    private val useCase = PrepareArCoordinateModelsUseCase()

    private fun coord(
        id: String,
        icon: String = "ic_pin",
        modelId: String? = null,
        modelScale: Double? = null,
        renderEnabled: Boolean = true,
    ) = CoordinateEntity(
        id = id, name = "P-$id", latitude = 41.0, longitude = -76.0, altitude = 100.0,
        timestamp = 1_000L, icon = icon, color = 0, modelId = modelId, modelScale = modelScale,
        renderEnabled = renderEnabled,
    )

    private fun model(id: String, defaultScale: Double = 2.0) = ModelEntity(
        id = id, name = "M-$id", fileName = "$id.glb", filePath = "/models/$id.glb",
        fileSize = 1L, dateAdded = 0L, defaultScale = defaultScale,
    )

    @Test fun linkedModel_resolvesFilePathAndDefaultPlacement() {
        val out = useCase(listOf(coord("a", modelId = "m1")), listOf(model("m1", defaultScale = 2.0)))
        assertEquals("m1", out[0].modelId)
        assertEquals("/models/m1.glb", out[0].modelFilePath)
        assertEquals(2.0f, out[0].placement.scale, 0f) // model default applied
    }

    @Test fun perCoordinateScale_overridesModelDefault() {
        val out = useCase(listOf(coord("a", modelId = "m1", modelScale = 3.0)), listOf(model("m1", defaultScale = 2.0)))
        assertEquals(3.0f, out[0].placement.scale, 0f) // override wins
    }

    @Test fun legacyIconConvention_resolvesModelId() {
        val out = useCase(listOf(coord("a", icon = "model:m1")), listOf(model("m1")))
        assertEquals("m1", out[0].modelId)
        assertEquals("/models/m1.glb", out[0].modelFilePath)
    }

    @Test fun missingModel_keepsCoordinateWithNullPath() {
        val out = useCase(listOf(coord("a", modelId = "ghost")), models = emptyList())
        assertEquals(1, out.size)
        assertEquals("ghost", out[0].modelId)
        assertNull(out[0].modelFilePath)
    }

    @Test fun noModel_yieldsIdentityPlacement() {
        val out = useCase(listOf(coord("a")), models = emptyList())
        assertEquals("identity", true, out[0].placement.isIdentity)
    }

    @Test fun renderDisabledCoordinate_isStillReturned() {
        // renderEnabled is applied at draw time, not as a list filter — every coordinate is prepared.
        val out = useCase(listOf(coord("a", renderEnabled = false)), models = emptyList())
        assertEquals(1, out.size)
        assertEquals(false, out[0].coordinate.renderEnabled)
    }
}
