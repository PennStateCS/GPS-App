package com.example.surveyingapp.data.repository.mapper

import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.displayIconKey
import com.example.surveyingapp.domain.model.linkedModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trips a fully-populated Coordinate through CoordinateEntity and back, guarding against
 * silent field loss in the mapping layer (data-critical).
 */
class CoordinateMappersTest {

    private val rich = Coordinate(
        id = "c1", name = "Point A", latitude = 41.347821, longitude = -76.022619, altitude = 399.02,
        timestamp = 1_000L, icon = "ic_pin", color = -12345,
        provider = "rs2-external", rtkStatus = "FIX", satsUsed = 22, satsVisible = 30,
        hdop = 0.7, vDop = 0.9, pDop = 1.1, horizontalAccuracyM = 0.02, verticalAccuracyM = 0.03,
        correctionSource = "NTRIP", correctionAgeS = 1.2, correctionStationId = "0123",
        altitudeMsl = 365.0, geoidSeparationM = 34.0, speedMps = 0.5, courseDeg = 84.4,
        timestampSource = "NMEA_ZDA", multipathIndex = 0.1, crsEpsg = 4326,
        easting = 500000.0, northing = 4570000.0, utmZone = "18T",
        note = "test note", captureMethod = "EXTERNAL_GNSS",
        averagedSamples = 150, averageDurationMs = 60_000L,
        stdLatM = 0.01, stdLonM = 0.011, stdAltM = 0.02, sourceDevice = "RS2+", appVersion = "1.0",
        modelId = "m1", iconKey = "ic_pin", renderEnabled = false, createdAt = 1_000L, updatedAt = 2_000L,
        modelScale = 2.5, modelYawDeg = 90.0, modelPitchDeg = 1.0, modelRollDeg = 2.0,
        modelVerticalOffsetM = 1.5, modelOriginOffsetXM = 0.1, modelOriginOffsetYM = 0.2, modelOriginOffsetZM = 0.3
    )

    @Test
    fun allFields_surviveRoundTrip() {
        val back = rich.toEntity().toDomain()

        assertEquals(rich.id, back.id)
        assertEquals(rich.name, back.name)
        assertEquals(rich.note, back.note)
        assertEquals(rich.latitude, back.latitude, 0.0)
        assertEquals(rich.longitude, back.longitude, 0.0)
        assertEquals(rich.altitude, back.altitude, 0.0)
        assertEquals(rich.provider, back.provider)               // rs2-external must NOT degrade
        assertEquals(rich.captureMethod, back.captureMethod)
        assertEquals(rich.rtkStatus, back.rtkStatus)
        assertEquals(rich.hdop, back.hdop)
        assertEquals(rich.vDop, back.vDop)
        assertEquals(rich.pDop, back.pDop)
        assertEquals(rich.horizontalAccuracyM, back.horizontalAccuracyM)
        assertEquals(rich.verticalAccuracyM, back.verticalAccuracyM)
        assertEquals(rich.satsUsed, back.satsUsed)
        assertEquals(rich.satsVisible, back.satsVisible)
        assertEquals(rich.correctionSource, back.correctionSource)
        assertEquals(rich.correctionAgeS, back.correctionAgeS)
        assertEquals(rich.correctionStationId, back.correctionStationId)
        assertEquals(rich.altitudeMsl, back.altitudeMsl)
        assertEquals(rich.geoidSeparationM, back.geoidSeparationM)
        assertEquals(rich.easting, back.easting)
        assertEquals(rich.northing, back.northing)
        assertEquals(rich.utmZone, back.utmZone)
        assertEquals(rich.averagedSamples, back.averagedSamples)
        assertEquals(rich.averageDurationMs, back.averageDurationMs)
        assertEquals(rich.stdLatM, back.stdLatM)
        assertEquals(rich.stdLonM, back.stdLonM)
        assertEquals(rich.stdAltM, back.stdAltM)
        assertEquals(rich.sourceDevice, back.sourceDevice)
        assertEquals(rich.appVersion, back.appVersion)
        assertEquals(rich.timestamp, back.timestamp)
        assertEquals(rich.timestampSource, back.timestampSource)
        // v10 fields
        assertEquals(rich.iconKey, back.iconKey)
        assertEquals(rich.modelId, back.modelId)
        assertEquals(rich.renderEnabled, back.renderEnabled)
        assertEquals(rich.createdAt, back.createdAt)
        assertEquals(rich.updatedAt, back.updatedAt)
        assertEquals(rich.modelScale, back.modelScale)
        assertEquals(rich.modelYawDeg, back.modelYawDeg)
        assertEquals(rich.modelPitchDeg, back.modelPitchDeg)
        assertEquals(rich.modelRollDeg, back.modelRollDeg)
        assertEquals(rich.modelVerticalOffsetM, back.modelVerticalOffsetM)
        assertEquals(rich.modelOriginOffsetXM, back.modelOriginOffsetXM)
        assertEquals(rich.modelOriginOffsetYM, back.modelOriginOffsetYM)
        assertEquals(rich.modelOriginOffsetZM, back.modelOriginOffsetZM)
    }

    @Test
    fun createdAt_defaultsToTimestamp_whenUnset() {
        // A coordinate written through a path that didn't set audit times (createdAt == 0) gets
        // seeded from `timestamp` by the mapper.
        val entity = rich.copy(createdAt = 0L, updatedAt = 0L).toEntity()
        assertEquals(rich.timestamp, entity.createdAt)
        assertEquals(rich.timestamp, entity.updatedAt)
    }

    @Test
    fun legacyModelIcon_resolvesToModelId() {
        val legacy = Coordinate(
            id = "c2", name = "P", latitude = 1.0, longitude = 2.0, altitude = 0.0,
            timestamp = 0L, icon = "model:legacy42", color = 0
        )
        assertEquals("legacy42", legacy.linkedModelId)
        assertNull(legacy.displayIconKey)
    }

    @Test
    fun normalIcon_resolvesToIconKey() {
        val plain = Coordinate(
            id = "c3", name = "P", latitude = 1.0, longitude = 2.0, altitude = 0.0,
            timestamp = 0L, icon = "ic_star", color = 0
        )
        assertNull(plain.linkedModelId)
        assertEquals("ic_star", plain.displayIconKey)
    }
}
