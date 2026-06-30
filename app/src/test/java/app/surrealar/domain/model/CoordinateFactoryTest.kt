package app.surrealar.domain.model

import app.surrealar.gnss.capture.CaptureResult
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CoordinateFactoryTest {

    private fun captureResult(
        latDeg: Double = 40.7128,
        lonDeg: Double = -74.0060,
        altEllipsoidalM: Double = 10.5,
        rtkStatus: RtkStatus? = RtkStatus.FIX,
        satsUsed: Int? = 14,
        satsVisible: Int? = 22,
        hdop: Double? = 0.8,
        vDop: Double? = 1.2,
        pDop: Double? = 1.5,
        hAccM: Double? = 0.012,
        vAccM: Double? = 0.018,
        diffAgeS: Double? = 2.3,
        correctionStationId: String? = "ABCD",
        samples: Int = 180,
        startedAt: Instant = Instant.ofEpochMilli(1_700_000_000_000L),
        endedAt: Instant = Instant.ofEpochMilli(1_700_000_060_000L),
        altMslM: Double? = 8.2,
        geoidSeparationM: Double? = 2.3,
        timestampSource: TimestampSource? = TimestampSource.NMEA_ZDA,
        multipathIndex: Double? = 0.15,
        stdDevEastM: Double? = 0.011,
        stdDevNorthM: Double? = 0.013,
        stdDevUpM: Double? = 0.021
    ) = CaptureResult(
        startedAt = startedAt,
        endedAt = endedAt,
        samples = samples,
        latDeg = latDeg,
        lonDeg = lonDeg,
        altEllipsoidalM = altEllipsoidalM,
        ecefStd = Triple(0.001, 0.001, 0.002),
        rtkStatus = rtkStatus,
        satsUsed = satsUsed,
        satsVisible = satsVisible,
        hdop = hdop,
        vDop = vDop,
        pDop = pDop,
        hAccM = hAccM,
        vAccM = vAccM,
        diffAgeS = diffAgeS,
        correctionStationId = correctionStationId,
        altMslM = altMslM,
        geoidSeparationM = geoidSeparationM,
        timestampSource = timestampSource,
        multipathIndex = multipathIndex,
        stdDevEastM = stdDevEastM,
        stdDevNorthM = stdDevNorthM,
        stdDevUpM = stdDevUpM
    )

    @Test
    fun `fromCaptureResult maps position fields`() {
        val result = captureResult()
        val coord = CoordinateFactory.fromCaptureResult(
            id = "test-id", name = "P1", note = null,
            color = 0xFF3F51B5.toInt(), iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = result
        )

        assertEquals(result.latDeg, coord.latitude, 1e-10)
        assertEquals(result.lonDeg, coord.longitude, 1e-10)
        assertEquals(result.altEllipsoidalM, coord.altitude, 1e-10)
        assertEquals(result.endedAt.toEpochMilli(), coord.timestamp)
    }

    @Test
    fun `fromCaptureResult maps all quality fields`() {
        val result = captureResult()
        val coord = CoordinateFactory.fromCaptureResult(
            id = "q", name = "Q", note = "survey note",
            color = 0, iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = result
        )

        assertEquals(RtkStatus.FIX.name, coord.rtkStatus)
        assertEquals(14, coord.satsUsed)
        assertEquals(22, coord.satsVisible)
        assertEquals(0.8, coord.hdop!!, 1e-10)
        assertEquals(1.2, coord.vDop!!, 1e-10)
        assertEquals(1.5, coord.pDop!!, 1e-10)
        assertEquals(0.012, coord.horizontalAccuracyM!!, 1e-10)
        assertEquals(0.018, coord.verticalAccuracyM!!, 1e-10)
        assertEquals(2.3, coord.correctionAgeS!!, 1e-10)
        assertEquals("ABCD", coord.correctionStationId)
        assertEquals(180, coord.averagedSamples)
        assertEquals(60_000L, coord.averageDurationMs)
        assertEquals("survey note", coord.note)
    }

    @Test
    fun `fromCaptureResult populates UTM fields for valid coordinates`() {
        val result = captureResult(latDeg = 40.7128, lonDeg = -74.0060)
        val coord = CoordinateFactory.fromCaptureResult(
            id = "utm", name = "UTM", note = null,
            color = 0, iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = result
        )

        assertNotNull("easting should not be null", coord.easting)
        assertNotNull("northing should not be null", coord.northing)
        // utmZone is zone + MGRS latitude-band letter; 40.7°N falls in band T.
        assertEquals("18T", coord.utmZone)
        assertEquals(4326, coord.crsEpsg)
    }

    @Test
    fun `fromCaptureResult maps provider to its storage string`() {
        val coord = CoordinateFactory.fromCaptureResult(
            id = "p", name = "P", note = null, color = 0, iconId = "pin",
            provider = Provider.INTERNAL, result = captureResult()
        )
        // fromCaptureResult normalizes the Provider enum to a storage string: INTERNAL -> "fused".
        assertEquals("fused", coord.provider)
    }

    @Test
    fun `fromCaptureResult preserves final-fix metadata`() {
        val coord = CoordinateFactory.fromCaptureResult(
            id = "m", name = "M", note = null, color = 0, iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = captureResult()
        )

        assertEquals(8.2, coord.altitudeMsl!!, 1e-10)
        assertEquals(2.3, coord.geoidSeparationM!!, 1e-10)
        assertEquals(TimestampSource.NMEA_ZDA.name, coord.timestampSource)
        assertEquals(0.15, coord.multipathIndex!!, 1e-10)
        // stdLat maps from North spread, stdLon from East spread.
        assertEquals(0.013, coord.stdLatM!!, 1e-10)
        assertEquals(0.011, coord.stdLonM!!, 1e-10)
        assertEquals(0.021, coord.stdAltM!!, 1e-10)
        // Correction source derived from the station id ("ABCD", length 4).
        assertEquals("Base Station ABCD", coord.correctionSource)
    }

    @Test
    fun `fromCaptureResult leaves final-fix metadata null when absent`() {
        val sparse = captureResult(
            altMslM = null, geoidSeparationM = null, timestampSource = null,
            multipathIndex = null, stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
            correctionStationId = null
        )
        val coord = CoordinateFactory.fromCaptureResult(
            id = "sm", name = "SM", note = null, color = 0, iconId = "pin",
            provider = Provider.OTHER, result = sparse
        )
        assertNull(coord.altitudeMsl)
        assertNull(coord.geoidSeparationM)
        assertNull(coord.timestampSource)
        assertNull(coord.multipathIndex)
        assertNull(coord.stdLatM)
        assertNull(coord.stdLonM)
        assertNull(coord.stdAltM)
        assertNull(coord.correctionSource)
    }

    @Test
    fun `antenna height offset is subtracted from ellipsoidal and MSL altitude and recorded`() {
        val result = captureResult(altEllipsoidalM = 10.5, altMslM = 8.2)
        val coord = CoordinateFactory.fromCaptureResult(
            id = "ah", name = "AH", note = null, color = 0, iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = result, antennaHeightM = 2.0
        )
        // Stored altitude is the ground mark = antenna altitude − pole height.
        assertEquals(8.5, coord.altitude, 1e-10)
        assertEquals(6.2, coord.altitudeMsl!!, 1e-10)
        // Geoid separation (a difference) is unaffected; the offset is recorded for provenance.
        assertEquals(2.3, coord.geoidSeparationM!!, 1e-10)
        assertEquals(2.0, coord.antennaHeightM!!, 1e-10)
    }

    @Test
    fun `zero antenna height leaves altitude unchanged and records no offset`() {
        val result = captureResult(altEllipsoidalM = 10.5, altMslM = 8.2)
        val coord = CoordinateFactory.fromCaptureResult(
            id = "z", name = "Z", note = null, color = 0, iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = result, antennaHeightM = 0.0
        )
        assertEquals(10.5, coord.altitude, 1e-10)
        assertEquals(8.2, coord.altitudeMsl!!, 1e-10)
        assertNull("no offset applied → no provenance value", coord.antennaHeightM)
    }

    @Test
    fun `antenna height offset with absent MSL does not crash`() {
        val result = captureResult(altEllipsoidalM = 10.5, altMslM = null)
        val coord = CoordinateFactory.fromCaptureResult(
            id = "nm", name = "NM", note = null, color = 0, iconId = "pin",
            provider = Provider.RS2_EXTERNAL, result = result, antennaHeightM = 1.5
        )
        assertEquals(9.0, coord.altitude, 1e-10)
        assertNull(coord.altitudeMsl)
        assertEquals(1.5, coord.antennaHeightM!!, 1e-10)
    }

    @Test
    fun `fromCaptureResult handles nullable quality fields gracefully`() {
        val sparse = captureResult(
            rtkStatus = null, satsUsed = null, satsVisible = null,
            hdop = null, vDop = null, pDop = null,
            hAccM = null, vAccM = null, diffAgeS = null, correctionStationId = null
        )
        val coord = CoordinateFactory.fromCaptureResult(
            id = "s", name = "S", note = null, color = 0, iconId = "pin",
            provider = Provider.OTHER, result = sparse
        )

        assertNull(coord.rtkStatus)
        assertNull(coord.satsUsed)
        assertNull(coord.satsVisible)
        assertNull(coord.hdop)
        assertNull(coord.vDop)
        assertNull(coord.pDop)
        assertNull(coord.horizontalAccuracyM)
        assertNull(coord.verticalAccuracyM)
        assertNull(coord.correctionAgeS)
        assertNull(coord.correctionStationId)
    }
}
