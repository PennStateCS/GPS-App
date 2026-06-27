package app.surrealar.ui.openinar

import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPlacementTest {

    private fun coord(
        modelScale: Double? = null,
        modelYawDeg: Double? = null,
        modelVerticalOffsetM: Double? = null
    ) = CoordinateEntity(
        id = "c1", name = "P", latitude = 1.0, longitude = 2.0, altitude = 0.0,
        timestamp = 0L, icon = "", color = 0,
        modelScale = modelScale, modelYawDeg = modelYawDeg, modelVerticalOffsetM = modelVerticalOffsetM
    )

    private fun model(defaultScale: Double = 1.0, defaultYawDeg: Double = 0.0) = ModelEntity(
        id = "m1", name = "M", fileName = "m.glb", filePath = "/m.glb",
        fileSize = 1L, dateAdded = 0L, defaultScale = defaultScale, defaultYawDeg = defaultYawDeg
    )

    @Test
    fun noModel_returnsIdentity() {
        val p = ModelPlacement.resolve(coord(), null)
        assertTrue(p.isIdentity)
    }

    @Test
    fun defaultsAreIdentity_forUnsetCoordinateAndDefaultModel() {
        val p = ModelPlacement.resolve(coord(), model())
        assertTrue("default placement must be identity so existing AR is unchanged", p.isIdentity)
    }

    @Test
    fun coordinateOverride_winsOverModelDefault() {
        val p = ModelPlacement.resolve(coord(modelScale = 3.0), model(defaultScale = 2.0))
        assertEquals(3.0f, p.scale, 1e-6f)
        assertFalse(p.isIdentity)
    }

    @Test
    fun modelDefault_usedWhenNoCoordinateOverride() {
        val p = ModelPlacement.resolve(coord(), model(defaultScale = 2.0, defaultYawDeg = 45.0))
        assertEquals(2.0f, p.scale, 1e-6f)
        assertEquals(45.0f, p.yawDeg, 1e-6f)
    }

    @Test
    fun verticalOffset_makesNonIdentity() {
        val p = ModelPlacement.resolve(coord(modelVerticalOffsetM = 1.0), model())
        assertEquals(1.0f, p.verticalOffsetM, 1e-6f)
        assertFalse(p.isIdentity)
    }
}
