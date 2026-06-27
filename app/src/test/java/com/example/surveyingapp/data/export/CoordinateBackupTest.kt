package com.example.surveyingapp.data.export

import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.FileType
import com.example.surveyingapp.domain.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateBackupTest {

    private fun richCoord(id: String = "c1", modelId: String? = "m1") = Coordinate(
        id = id, name = "Point A", latitude = 41.3478, longitude = -76.0226, altitude = 399.0,
        timestamp = 1_000L, icon = "model:m1", color = -1,
        provider = "rs2-external", rtkStatus = "FIX", satsUsed = 22, satsVisible = 30,
        hdop = 0.7, vDop = 0.9, pDop = 1.1, horizontalAccuracyM = 0.02, verticalAccuracyM = 0.03,
        correctionSource = "NTRIP", correctionAgeS = 1.2, correctionStationId = "0123",
        altitudeMsl = 365.0, geoidSeparationM = 34.0, speedMps = 0.0, courseDeg = 0.0,
        timestampSource = "NMEA_ZDA", multipathIndex = 0.1, crsEpsg = 4326,
        easting = 500000.0, northing = 4570000.0, utmZone = "18T",
        note = "test, with comma", captureMethod = "EXTERNAL_GNSS",
        averagedSamples = 150, averageDurationMs = 60_000L,
        stdLatM = 0.01, stdLonM = 0.01, stdAltM = 0.02, sourceDevice = "RS2+", appVersion = "1.0",
        modelId = modelId, iconKey = null, renderEnabled = false, createdAt = 1_000L, updatedAt = 2_000L,
        modelScale = 2.5, modelYawDeg = 90.0, modelPitchDeg = 0.0, modelRollDeg = 0.0,
        modelVerticalOffsetM = 1.5, modelOriginOffsetXM = 0.1, modelOriginOffsetYM = 0.2, modelOriginOffsetZM = 0.3
    )

    private fun model(id: String = "m1") = Model(
        id = id, name = "Tower", fileName = "tower.glb", filePath = "/tower.glb",
        fileSize = 1L, dateAdded = 0L, fileType = FileType.MESH_MODEL
    )

    @Test
    fun roundTrip_preservesAllKeyFields() {
        val json = CoordinateBackup.export(listOf(richCoord()), listOf(model()), "1.0")
        assertTrue(CoordinateBackup.isFullBackup(json))

        val result = CoordinateBackup.parse(json)
        assertEquals(1, result.coordinates.size)
        assertTrue(result.missingModelRefs.isEmpty())
        assertTrue(result.skippedInvalid.isEmpty())

        val c = result.coordinates.first()
        assertEquals("rs2-external", c.provider)
        assertEquals("EXTERNAL_GNSS", c.captureMethod)
        assertEquals("FIX", c.rtkStatus)
        assertEquals(0.02, c.horizontalAccuracyM!!, 1e-9)
        assertEquals("18T", c.utmZone)
        assertEquals("test, with comma", c.note)
        assertEquals("m1", c.modelId)
        assertFalse(c.renderEnabled)
        assertEquals(2.5, c.modelScale!!, 1e-9)
        assertEquals(90.0, c.modelYawDeg!!, 1e-9)
        assertEquals(1.5, c.modelVerticalOffsetM!!, 1e-9)
        assertEquals(150, c.averagedSamples)
        assertEquals(2_000L, c.updatedAt)
    }

    @Test
    fun flagsMissingModelButStillImports() {
        // Coordinate references m1, but the backup contains no models.
        val json = CoordinateBackup.export(listOf(richCoord(modelId = "m1")), emptyList(), null)
        val result = CoordinateBackup.parse(json)
        assertEquals(1, result.coordinates.size)          // still imported
        assertEquals(1, result.missingModelRefs.size)        // but flagged
    }

    @Test
    fun skipsInvalidCoordinates() {
        val bad = richCoord(id = "bad").copy(latitude = 0.0, longitude = 0.0)
        val json = CoordinateBackup.export(listOf(richCoord(id = "good"), bad), listOf(model()), null)
        val result = CoordinateBackup.parse(json)
        assertEquals(1, result.coordinates.size)
        assertEquals("good", result.coordinates.first().id)
        assertEquals(1, result.skippedInvalid.size)
    }
}
