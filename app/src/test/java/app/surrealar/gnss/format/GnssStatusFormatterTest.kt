package app.surrealar.gnss.format

import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the user-facing GNSS status wording. If a future refactor changes a label, these tests
 * fail — protecting the toolbar / capture / settings text from silent drift.
 */
class GnssStatusFormatterTest {

    // ── source ────────────────────────────────────────────────────────────────
    @Test fun `source label - internal`() {
        assertEquals("Internal", GnssStatusFormatter.formatSource(isInternal = true))
    }

    @Test fun `source label - external`() {
        assertEquals("External", GnssStatusFormatter.formatSource(isInternal = false))   // neutral fallback
    }

    // ── fix status (external receiver: full RTK ladder) ──────────────────────────
    @Test fun `external fix status labels`() {
        fun ext(s: RtkStatus) = GnssStatusFormatter.formatFixStatus(s, isInternal = false)
        assertEquals("No Fix", ext(RtkStatus.NONE))
        assertEquals("Single", ext(RtkStatus.SINGLE))
        assertEquals("DGPS", ext(RtkStatus.DGPS))
        assertEquals("Float", ext(RtkStatus.FLOAT))
        assertEquals("Fix", ext(RtkStatus.FIX))
        assertEquals("DR", ext(RtkStatus.DEAD_RECKONING))
        assertEquals("No Fix", ext(RtkStatus.INVALID))
    }

    // ── fix status (internal GPS: collapsed) ─────────────────────────────────────
    @Test fun `internal fix status collapses to GPS or No Fix`() {
        fun int(s: RtkStatus) = GnssStatusFormatter.formatFixStatus(s, isInternal = true)
        assertEquals("No Fix", int(RtkStatus.NONE))
        assertEquals("No Fix", int(RtkStatus.INVALID))
        assertEquals("Single", int(RtkStatus.SINGLE))
        assertEquals("Single", int(RtkStatus.FIX))
        assertEquals("Single", int(RtkStatus.FLOAT))
    }

    // ── capture fix status (string-based, "Unknown" fallback) ────────────────────
    @Test fun `capture fix status maps known names`() {
        fun cap(s: String?) = GnssStatusFormatter.formatCaptureFixStatus(s)
        assertEquals("Fix", cap("FIX"))
        assertEquals("Float", cap("FLOAT"))
        assertEquals("DGPS", cap("DGPS"))
        assertEquals("Single", cap("SINGLE"))
        assertEquals("No Fix", cap("NONE"))
        assertEquals("Fix", cap("fix"))            // case-insensitive
    }

    @Test fun `capture fix status falls back to Unknown`() {
        fun cap(s: String?) = GnssStatusFormatter.formatCaptureFixStatus(s)
        assertEquals("Unknown", cap("DEAD_RECKONING"))
        assertEquals("Unknown", cap("INVALID"))
        assertEquals("Unknown", cap("anything-else"))
        assertEquals("Unknown", cap(null))
    }

    // ── accuracy ─────────────────────────────────────────────────────────────────
    @Test fun `accuracy formatting - two decimals with plus-minus and unit`() {
        assertEquals("±0.02 m", GnssStatusFormatter.formatAccuracyMeters(0.02))
        assertEquals("±1.20 m", GnssStatusFormatter.formatAccuracyMeters(1.2))
        assertEquals("±0.00 m", GnssStatusFormatter.formatAccuracyMeters(0.0))
        assertEquals("±12.35 m", GnssStatusFormatter.formatAccuracyMeters(12.345))
    }

    // ── satellites ───────────────────────────────────────────────────────────────
    @Test fun `satellites formatting - used over visible`() {
        assertEquals("12/20", GnssStatusFormatter.formatSatellites(12, 20))
        assertEquals("0/0", GnssStatusFormatter.formatSatellites(0, 0))
        assertEquals("8/8", GnssStatusFormatter.formatSatellites(8, 8))
    }

    // ── correction age ───────────────────────────────────────────────────────────
    @Test fun `correction age - null when unreported`() {
        assertNull(GnssStatusFormatter.formatCorrectionAge(null))
    }

    @Test fun `correction age - one decimal under 10 s, rounded above`() {
        assertEquals("Age 1.8s", GnssStatusFormatter.formatCorrectionAge(1.8))   // used to read "1s"
        assertEquals("Age 3.9s", GnssStatusFormatter.formatCorrectionAge(3.9))
        assertEquals("Age 0.0s", GnssStatusFormatter.formatCorrectionAge(0.0))
        assertEquals("Age 13s", GnssStatusFormatter.formatCorrectionAge(12.6))
        assertEquals("Age 30s", GnssStatusFormatter.formatCorrectionAge(30.0))
        assertEquals("1.8s", GnssStatusFormatter.formatAgeSeconds(1.8))
        assertEquals("10s", GnssStatusFormatter.formatAgeSeconds(9.96))           // rounds up across the boundary
    }

    @Test fun `correction age - negative is treated as not reported`() {
        assertNull(GnssStatusFormatter.formatCorrectionAge(-1.0))
    }
}
