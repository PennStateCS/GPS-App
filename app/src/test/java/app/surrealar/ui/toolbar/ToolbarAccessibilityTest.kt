package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Step 7: every token gets a spoken description from the mapper. */
class ToolbarAccessibilityTest {

    private fun fix(rtk: RtkStatus, hAcc: Double?, age: Double? = 1.2, provider: Provider = Provider.EXTERNAL_TCP) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.234567, lonDeg = -76.123456, altEllipsoidalM = 300.0, altMslM = 422.81, geoidSeparationM = -33.0,
        hDop = 1.0, vDop = 1.2, pDop = null, hAccM = hAcc, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 18, satsVisible = 24, diffAgeS = age, speedMps = null, courseDeg = null,
    )

    private fun live(f: Fix, source: LocationSourceType = LocationSourceType.EXTERNAL) =
        (GnssToolbarStateMapper.map(source, f, SkySnapshot(), "RS2+") as ToolbarMapResult.Render).state

    @Test fun `live external fix`() {
        val s = live(fix(RtkStatus.FIX, hAcc = 0.015))
        assertEquals("Source: RS2+ receiver", s.sourceDescription)
        assertEquals("Solution: RTK fixed", s.fixDescription)
        assertEquals("Accuracy plus or minus 1.5 centimetres", s.accuracyDescription)
        assertEquals("Correction age 1.2 seconds", s.ageDescription)
        assertEquals("Satellites 18 used of 24", s.satsDescription)
        assertEquals("Position 41.234567, -76.123456", s.coordDescription)
        assertEquals("Height ALT 422.81 m", s.altitudeDescription)
    }

    @Test fun `estimated accuracy and metres`() {
        // No GST: DOP × UERE for a FLOAT survey receiver = 1.0 × 0.25 m × 1.071 ≈ 0.27 m.
        assertEquals("Accuracy plus or minus 26.8 centimetres, estimated", live(fix(RtkStatus.FLOAT, hAcc = null)).accuracyDescription)
        assertEquals("Accuracy plus or minus 3.20 metres", live(fix(RtkStatus.SINGLE, hAcc = 3.2)).accuracyDescription)
    }

    @Test fun `internal source`() {
        val s = live(fix(RtkStatus.SINGLE, hAcc = 4.0, age = null, provider = Provider.INTERNAL), LocationSourceType.INTERNAL)
        assertEquals("Source: device GNSS", s.sourceDescription)
        assertEquals("Solution: GPS", s.fixDescription)
        assertNull(s.ageDescription)
    }

    @Test fun `no-fix states explain themselves`() {
        val m = GnssToolbarStateMapper
        assertEquals("Connecting to receiver",
            m.noFix(LocationSourceType.EXTERNAL, "RS2+", ToolbarLinkInputs(ExternalLinkPhase.CONNECTING), null, 10_000).fixDescription)
        assertEquals("Receiver connected, no data for 12 seconds",
            m.noFix(LocationSourceType.EXTERNAL, "RS2+", ToolbarLinkInputs(ExternalLinkPhase.CONNECTED, lastRawNmeaAtMs = 0L), null, 12_000).fixDescription)
        assertEquals("Location is off",
            m.noFix(LocationSourceType.INTERNAL, "RS2+", ToolbarLinkInputs(locationEnabled = false), null, 0).fixDescription)
        val lost = m.noFix(LocationSourceType.EXTERNAL, "RS2+", ToolbarLinkInputs(ExternalLinkPhase.CONNECTING),
            LastFix(fix(RtkStatus.FIX, 0.01), atMs = 0L, coords = null), nowMs = 12_000)
        assertEquals("Position lost 12 seconds ago, showing the last position", lost.fixDescription)
        assertEquals("Last known position 41.234567, -76.123456", lost.coordDescription)
        assertNull(lost.accuracyDescription); assertNull(lost.satsDescription)
        assertEquals("Position not available", m.waiting(LocationSourceType.EXTERNAL, "RS2+").coordDescription)
        assertEquals("GNSS status error, retrying", m.error(LocationSourceType.EXTERNAL, "RS2+").fixDescription)
    }

    @Test fun `battery description`() {
        assertEquals("Receiver battery 80 percent, charging", GnssToolbarStateMapper.batteryDescription(80, true))
        assertEquals("Receiver battery 15 percent", GnssToolbarStateMapper.batteryDescription(15, false))
        assertEquals("Receiver battery unknown", GnssToolbarStateMapper.batteryDescription(null, null))
    }
}
