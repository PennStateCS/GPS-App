package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Verifies the pure toolbar state mapping: source-switch protections (wrong-provider / stale /
 * waiting) and the display-ready fields. No Android views are touched.
 */
class GnssToolbarStateMapperTest {

    private val now = 1_700_000_000_000L // fixed "current time" for deterministic age checks

    private fun fix(
        provider: Provider,
        rtk: RtkStatus,
        lat: Double = 40.7963,
        lon: Double = -77.8570,
        hAccM: Double? = 0.02,
        satsUsed: Int? = 10,
        satsVisible: Int? = 14,
        ageMs: Long = 0L,
        altMslM: Double? = 300.0
    ): Fix = Fix(
        provider = provider,
        timeUtc = Instant.ofEpochMilli(now - ageMs),
        timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = lat,
        lonDeg = lon,
        altEllipsoidalM = 312.0,
        altMslM = altMslM,
        geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null,
        hAccM = hAccM, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtk,
        satsUsed = satsUsed,
        satsVisible = satsVisible,
        diffAgeS = 1.0,
        speedMps = null, courseDeg = null
    )

    private fun render(result: ToolbarMapResult): GnssToolbarState {
        assertTrue("expected Render but got $result", result is ToolbarMapResult.Render)
        return (result as ToolbarMapResult.Render).state
    }

    // 1 — Internal active, no fix yet → waiting
    @Test fun `internal no fix shows waiting blank`() {
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.INTERNAL, null, SkySnapshot()))
        assertEquals("Internal", s.sourceText)
        assertTrue(s.isWaiting)
        assertEquals("No fix", s.fixText)   // says why (was a bare "--")
        assertEquals("--", s.latLonText)
        assertNull(s.satelliteText)
        assertNull(s.accuracyText)
        assertFalse("internal hides receiver battery", s.batteryVisible)
        assertFalse(s.isExternal)
    }

    @Test fun `unknown satellite count shows no satellite text rather than 0`() {
        val s = render(
            GnssToolbarStateMapper.map(
                LocationSourceType.INTERNAL,
                fix(Provider.INTERNAL, RtkStatus.SINGLE, satsUsed = null, satsVisible = null),
                SkySnapshot()
            )
        )
        assertNull(s.satelliteText)
    }

    // 2 — Internal active, valid internal fix
    @Test fun `internal valid fix populates fields`() {
        val s = render(
            GnssToolbarStateMapper.map(
                LocationSourceType.INTERNAL,
                fix(Provider.INTERNAL, RtkStatus.FIX, satsUsed = 9, satsVisible = 12),
                SkySnapshot()
            )
        )
        assertEquals("Internal", s.sourceText)
        assertEquals("GPS", s.fixText)               // internal collapses FIX → GPS
        assertEquals("40.796300, -77.857000", s.latLonText)
        assertEquals("±0.02 m", s.accuracyText)
        assertEquals("9/12", s.satelliteText)
        assertEquals("300.00 m", s.altitudeText)
        assertFalse(s.isExternal)
        assertFalse(s.isWaiting)
    }

    // 3 — External selected/active, no current fix yet → waiting
    @Test fun `external no fix shows waiting with RS2 label`() {
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, null, SkySnapshot()))
        assertEquals("External", s.sourceText)   // no profile passed: neutral fallback, never "RS2+"
        assertTrue(s.isWaiting)
        assertEquals("Connecting…", s.fixText)   // says why (was "Waiting")
        assertEquals("--", s.latLonText)             // no stale coordinates
        assertNull(s.satelliteText)
        assertTrue(s.isExternal)
        assertTrue("external shows receiver battery slot", s.batteryVisible)
    }

    // 4 — External active, valid Float fix
    @Test fun `external float fix populates with Float status`() {
        val s = render(
            GnssToolbarStateMapper.map(
                LocationSourceType.EXTERNAL,
                fix(Provider.EXTERNAL_TCP, RtkStatus.FLOAT, hAccM = 0.18, satsUsed = 20, satsVisible = 28),
                SkySnapshot()
            )
        )
        assertEquals("External", s.sourceText)   // no profile passed: neutral fallback, never "RS2+"
        assertEquals("Float", s.fixText)
        assertEquals("40.796300, -77.857000", s.latLonText)
        assertEquals("±0.18 m", s.accuracyText)
        assertEquals("20/28", s.satelliteText)
        assertTrue(s.isExternal)
    }

    // 5 — External active, valid Fixed fix
    @Test fun `external fixed fix shows Fixed status`() {
        val s = render(
            GnssToolbarStateMapper.map(
                LocationSourceType.EXTERNAL,
                fix(Provider.EXTERNAL_TCP, RtkStatus.FIX, hAccM = 0.015),
                SkySnapshot()
            )
        )
        assertEquals("Fixed", s.fixText)
        assertEquals("±0.02 m", s.accuracyText)      // 0.015 rounds to 0.02
        assertEquals("40.796300, -77.857000", s.latLonText)
    }

    // 6 — Switch External → Internal clears external-specific UI
    @Test fun `switching to internal hides external battery and coordinates`() {
        val s = GnssToolbarStateMapper.waiting(LocationSourceType.INTERNAL)
        assertEquals("Internal", s.sourceText)
        assertFalse("no RS2 battery under Internal", s.batteryVisible)
        assertEquals("--", s.latLonText)             // no old external coordinates
        assertNull(s.satelliteText)
        assertFalse(s.isExternal)
    }

    // 7 — Wrong-provider fixes are ignored
    @Test fun `internal active ignores external fix`() {
        val r = GnssToolbarStateMapper.map(
            LocationSourceType.INTERNAL, fix(Provider.EXTERNAL_TCP, RtkStatus.FIX), SkySnapshot()
        )
        assertTrue(r is ToolbarMapResult.Ignore)
        assertEquals("wrong-provider", (r as ToolbarMapResult.Ignore).reason)
    }

    @Test fun `external active ignores internal fix`() {
        val r = GnssToolbarStateMapper.map(
            LocationSourceType.EXTERNAL, fix(Provider.INTERNAL, RtkStatus.SINGLE), SkySnapshot()
        )
        assertTrue(r is ToolbarMapResult.Ignore)
        assertEquals("wrong-provider", (r as ToolbarMapResult.Ignore).reason)
    }

    // 8 — A skewed device clock must not freeze the toolbar: liveness comes from the GNSS feed
    // (currentFix goes null when fixes stop), not from comparing GNSS time with the tablet clock.
    @Test fun `fix with GNSS time far from device clock still renders`() {
        val s = render(GnssToolbarStateMapper.map(
            LocationSourceType.EXTERNAL,
            fix(Provider.EXTERNAL_TCP, RtkStatus.FIX, ageMs = 120_000L),
            SkySnapshot()
        ))
        assertEquals("Fixed", s.fixText)
        assertFalse(s.isWaiting)
    }

    // bonus — invalid coordinates render waiting (never leave older values on screen)
    @Test fun `invalid coordinates render waiting`() {
        val s = render(GnssToolbarStateMapper.map(
            LocationSourceType.EXTERNAL,
            fix(Provider.EXTERNAL_TCP, RtkStatus.FIX, lat = 200.0, lon = 0.0),
            SkySnapshot()
        ))
        assertTrue(s.isWaiting)
        assertEquals("--", s.latLonText)
    }

    // internal GNSS is graded on a phone scale; external stays survey-graded
    @Test fun `accuracy colour uses phone thresholds for internal and survey thresholds for external`() {
        assertEquals(GnssStatusLevel.SUCCESS, GnssToolbarStateMapper.accuracyLevel(3.0, isInternal = true))
        assertEquals(GnssStatusLevel.WARNING, GnssToolbarStateMapper.accuracyLevel(10.9, isInternal = true))
        assertEquals(GnssStatusLevel.ERROR, GnssToolbarStateMapper.accuracyLevel(20.0, isInternal = true))
        assertEquals(GnssStatusLevel.SUCCESS, GnssToolbarStateMapper.accuracyLevel(0.02, isInternal = false))
        assertEquals(GnssStatusLevel.WARNING, GnssToolbarStateMapper.accuracyLevel(0.5, isInternal = false))
        assertEquals(GnssStatusLevel.ERROR, GnssToolbarStateMapper.accuracyLevel(3.0, isInternal = false))
    }

    // accuracy falls back to a DOP × UERE estimate, marked "~", when the receiver reports none
    @Test fun `estimated accuracy is shown with tilde when not reported`() {
        val f = fix(Provider.INTERNAL, RtkStatus.SINGLE, hAccM = null).copy(hDop = 1.0)
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.INTERNAL, f, SkySnapshot()))
        assertTrue("was ${s.accuracyText}", s.accuracyText!!.startsWith("~±"))
    }

    // sats fall back to fix counts when sky is empty; hidden when both are zero
    @Test fun `satellites hidden when none reported`() {
        val s = render(
            GnssToolbarStateMapper.map(
                LocationSourceType.EXTERNAL,
                fix(Provider.EXTERNAL_TCP, RtkStatus.FIX, satsUsed = 0, satsVisible = 0),
                SkySnapshot()
            )
        )
        assertNull(s.satelliteText)
    }

    // accuracy hidden when not reported
    @Test fun `accuracy hidden when null`() {
        val s = render(
            GnssToolbarStateMapper.map(
                LocationSourceType.EXTERNAL,
                fix(Provider.EXTERNAL_TCP, RtkStatus.FIX, hAccM = null),
                SkySnapshot()
            )
        )
        assertNull(s.accuracyText)
    }
    // ── 1c: source label comes from the active profile, else "External" ──────────

    @Test fun `waiting shows the active profile's short label`() {
        assertEquals("RS4", GnssToolbarStateMapper.waiting(LocationSourceType.EXTERNAL, "RS4").sourceText)
        assertEquals("EXT", GnssToolbarStateMapper.waiting(LocationSourceType.EXTERNAL, "EXT").sourceText)
    }

    @Test fun `without a known profile the external label is External, never RS2+`() {
        assertEquals("External", GnssToolbarStateMapper.waiting(LocationSourceType.EXTERNAL).sourceText)
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, null, SkySnapshot()))
        assertEquals("External", s.sourceText)
    }

    @Test fun `error state keeps the source label and clears live fields`() {
        val s = GnssToolbarStateMapper.error(LocationSourceType.EXTERNAL, "RS2+")
        assertEquals("RS2+", s.sourceText)
        assertEquals("Error", s.fixText)
        assertEquals(GnssStatusLevel.ERROR, s.fixLevel)
        assertEquals("--", s.latLonText); assertEquals(null, s.accuracyText); assertEquals(null, s.satelliteText)
    }
}