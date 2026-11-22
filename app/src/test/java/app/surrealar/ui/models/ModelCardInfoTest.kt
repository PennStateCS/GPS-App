package app.surrealar.ui.models

import app.surrealar.domain.model.FileType
import app.surrealar.domain.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCardInfoTest {

    private fun model(name: String, fileName: String, lat: Double? = null, lon: Double? = null) = Model(
        id = "m", name = name, fileName = fileName, filePath = "/x/$fileName", fileSize = 1,
        dateAdded = 0, fileType = FileType.OTHER, embeddedLatitude = lat, embeddedLongitude = lon
    )

    @Test
    fun linkedCounts_countsColumnAndLegacyIconOncePerCoordinate() {
        val counts = ModelCardInfo.linkedCounts(
            listOf(
                "m1" to "pin",
                null to "model:m1",
                "m1" to "model:m1",   // same model both ways: counted once
                "m2" to "model:m3",   // different models: one each
                null to "model:",     // empty id ignored
                null to "flag",
                "" to null
            )
        )
        assertEquals(mapOf("m1" to 3, "m2" to 1, "m3" to 1), counts)
    }

    @Test
    fun nameRepeatsFileName_onlyForDefaultName() {
        assertTrue(ModelCardInfo.nameRepeatsFileName(model("house", "house.glb")))
        assertTrue(ModelCardInfo.nameRepeatsFileName(model("my.bridge", "my.bridge.gltf")))
        assertFalse(ModelCardInfo.nameRepeatsFileName(model("North barn", "house.glb")))
    }

    @Test
    fun typeAndSizeLabel_formats() {
        assertEquals("GLB · 119.4 KB", ModelCardInfo.typeAndSizeLabel("a.glb", (119.4 * 1024).toLong() + 1))
        assertEquals("GLTF · 2.0 MB", ModelCardInfo.typeAndSizeLabel("a.gltf", 2L * 1024 * 1024))
        assertEquals("512 B", ModelCardInfo.typeAndSizeLabel("noext", 512))
    }

    @Test
    fun isGeoreferenced_needsBothLatAndLon() {
        assertTrue(ModelCardInfo.isGeoreferenced(model("a", "a.glb", 41.0, -75.0)))
        assertFalse(ModelCardInfo.isGeoreferenced(model("a", "a.glb", 41.0, null)))
        assertFalse(ModelCardInfo.isGeoreferenced(model("a", "a.glb")))
    }
}
