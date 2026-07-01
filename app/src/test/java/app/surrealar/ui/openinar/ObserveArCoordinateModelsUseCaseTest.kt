package app.surrealar.ui.openinar

import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.data.local.dao.ModelDao
import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

/**
 * Verifies the AR data-loading boundary: it streams coordinate-table changes, snapshots models per
 * emission, and maps both through [PrepareArCoordinateModelsUseCase]. Placement/missing-model rules
 * themselves are covered by [PrepareArCoordinateModelsUseCaseTest]; here we check the Flow wiring.
 */
class ObserveArCoordinateModelsUseCaseTest {

    private fun coord(
        id: String, icon: String = "ic_pin", modelId: String? = null,
        modelScale: Double? = null, renderEnabled: Boolean = true,
    ) = CoordinateEntity(
        id = id, name = "P-$id", latitude = 41.0, longitude = -76.0, altitude = 100.0,
        timestamp = 1_000L, icon = icon, color = 0, modelId = modelId, modelScale = modelScale,
        renderEnabled = renderEnabled,
    )

    private fun model(id: String, defaultScale: Double = 2.0) = ModelEntity(
        id = id, name = "M-$id", fileName = "$id.glb", filePath = "/models/$id.glb",
        fileSize = 1L, dateAdded = 0L, defaultScale = defaultScale,
    )

    private fun boundary(
        coords: MutableStateFlow<List<CoordinateEntity>>,
        models: List<ModelEntity>,
    ): ObserveArCoordinateModelsUseCase {
        val coordinateDao = Mockito.mock(CoordinateDao::class.java)
        val modelDao = Mockito.mock(ModelDao::class.java)
        Mockito.`when`(coordinateDao.observeAll()).thenReturn(coords)
        runBlocking { Mockito.`when`(modelDao.getAllModelsList()).thenReturn(models) }
        return ObserveArCoordinateModelsUseCase(coordinateDao, modelDao, PrepareArCoordinateModelsUseCase())
    }

    @Test fun linkedModel_resolvesFilePathAndDefaultPlacement() = runTest {
        val out = boundary(MutableStateFlow(listOf(coord("a", modelId = "m1"))), listOf(model("m1", 2.0)))().first()
        assertEquals("m1", out[0].modelId)
        assertEquals("/models/m1.glb", out[0].modelFilePath)
        assertEquals(2.0f, out[0].placement.scale, 0f)        // model default applied
    }

    @Test fun perCoordinateScale_overridesModelDefault() = runTest {
        val out = boundary(MutableStateFlow(listOf(coord("a", modelId = "m1", modelScale = 3.0))), listOf(model("m1", 2.0)))().first()
        assertEquals(3.0f, out[0].placement.scale, 0f)        // override wins
    }

    @Test fun missingModel_keepsCoordinateWithNullPath() = runTest {
        val out = boundary(MutableStateFlow(listOf(coord("a", modelId = "ghost"))), emptyList())().first()
        assertEquals(1, out.size)
        assertEquals("ghost", out[0].modelId)
        assertNull(out[0].modelFilePath)
    }

    @Test fun noModel_yieldsIdentityPlacement() = runTest {
        val out = boundary(MutableStateFlow(listOf(coord("a"))), emptyList())().first()
        assertEquals(true, out[0].placement.isIdentity)
    }

    @Test fun renderDisabledCoordinate_isPreserved() = runTest {
        val out = boundary(MutableStateFlow(listOf(coord("a", renderEnabled = false))), emptyList())().first()
        assertEquals(1, out.size)
        assertEquals(false, out[0].coordinate.renderEnabled)
    }

    @Test fun reEmitsWhenCoordinatesChange() = runTest {
        val coords = MutableStateFlow(listOf(coord("a")))
        val flow = boundary(coords, emptyList())()
        assertEquals(listOf("a"), flow.first().map { it.coordinate.id })
        coords.value = listOf(coord("b"), coord("c"))
        assertEquals(listOf("b", "c"), flow.first().map { it.coordinate.id })
    }

    // A DB failure must degrade to an empty list, not crash the AR screen (the flow feeds the
    // ViewModel's stateIn, whose uncaught upstream exception would otherwise take down the fragment).

    @Test fun coordinateStreamError_emitsEmptyListInsteadOfThrowing() = runTest {
        val coordinateDao = Mockito.mock(CoordinateDao::class.java)
        val modelDao = Mockito.mock(ModelDao::class.java)
        Mockito.`when`(coordinateDao.observeAll()).thenReturn(flow { throw RuntimeException("db read failed") })
        val useCase = ObserveArCoordinateModelsUseCase(coordinateDao, modelDao, PrepareArCoordinateModelsUseCase())
        assertEquals(emptyList<CoordWithModel>(), useCase().first())
    }

    @Test fun modelLookupError_emitsEmptyListInsteadOfThrowing() = runTest {
        val coordinateDao = Mockito.mock(CoordinateDao::class.java)
        val modelDao = Mockito.mock(ModelDao::class.java)
        Mockito.`when`(coordinateDao.observeAll()).thenReturn(MutableStateFlow(listOf(coord("a", modelId = "m1"))))
        runBlocking { Mockito.`when`(modelDao.getAllModelsList()).thenThrow(RuntimeException("query failed")) }
        val useCase = ObserveArCoordinateModelsUseCase(coordinateDao, modelDao, PrepareArCoordinateModelsUseCase())
        assertEquals(emptyList<CoordWithModel>(), useCase().first())
    }
}
