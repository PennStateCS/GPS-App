package app.surrealar.data.repository.mapper

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.linkedModelId
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

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
        modelVerticalOffsetM = 1.5, modelOriginOffsetXM = 0.1, modelOriginOffsetYM = 0.2, modelOriginOffsetZM = 0.3,
            modelMoveEastM = 1.25, modelMoveNorthM = -0.5, modelMoveUpM = 0.1
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
        // A legacy "rs2-external" token keeps its meaning (external receiver over TCP) and is written
        // back as the receiver-neutral token.
        assertEquals("external-tcp", back.provider)
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
        assertEquals(rich.modelMoveEastM, back.modelMoveEastM)
        assertEquals(rich.modelMoveNorthM, back.modelMoveNorthM)
        assertEquals(rich.modelMoveUpM, back.modelMoveUpM)
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

    private fun fix(altEllipsoidalM: Double?) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.ofEpochMilli(1_000L),
        timestampSource = TimestampSource.NMEA_ZDA, latDeg = 41.0, lonDeg = -76.0,
        altEllipsoidalM = altEllipsoidalM, altMslM = null, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 28, diffAgeS = null,
        speedMps = null, courseDeg = null
    )

    @Test
    fun toEntityFromFix_missingAltitude_throwsInsteadOfStoringZero() {
        // A fix without ellipsoidal height must not be silently saved as altitude 0.0.
        assertThrows(IllegalArgumentException::class.java) {
            toEntityFromFix(id = "c4", name = "P", icon = "ic_pin", color = 0, note = null, fix = fix(null))
        }
    }

    @Test
    fun toEntityFromFix_presentAltitude_mapsThrough() {
        val e = toEntityFromFix(id = "c5", name = "P", icon = "ic_pin", color = 0, note = null, fix = fix(399.02))
        assertEquals(399.02, e.altitude, 0.0)
    }

    @Test
    fun captureCorrectionSource_isStoredNotDropped() {
        // CoordinateFactory used to write "Base Station 1116" / "RTCM", which valueOf() rejected → null.
        for (legacy in listOf("Base Station 1116", "RTCM", "Station 12345", "UNKNOWN")) {
            assertEquals(legacy, app.surrealar.domain.model.CorrectionSource.UNKNOWN,
                rich.copy(correctionSource = legacy).toEntity().correctionSource)
        }
        assertEquals(app.surrealar.domain.model.CorrectionSource.NTRIP,
            rich.copy(correctionSource = "ntrip").toEntity().correctionSource)
        assertNull(rich.copy(correctionSource = null).toEntity().correctionSource)
    }

    @Test
    fun rtkStatusAliases_mapLikeTheRoomConverter() {
        assertEquals(RtkStatus.FIX, rich.copy(rtkStatus = "RTK_FIXED").toEntity().rtkStatus)
        assertEquals(RtkStatus.FLOAT, rich.copy(rtkStatus = "RTK_FLOAT").toEntity().rtkStatus)
        assertEquals(RtkStatus.FIX, rich.copy(rtkStatus = "fixed").toEntity().rtkStatus)
        assertEquals(RtkStatus.DEAD_RECKONING, rich.copy(rtkStatus = "DR").toEntity().rtkStatus)
    }
}
