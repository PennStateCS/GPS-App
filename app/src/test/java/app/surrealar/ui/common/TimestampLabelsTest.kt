package app.surrealar.ui.common

import app.surrealar.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the timestamp-source label/symbol wording and badge formatting (moved from `gnss.ui`).
 * The time portion is timezone-dependent, so only the deterministic badge/label/symbol parts are
 * asserted (via exact match or suffix) — enough to catch any wording drift.
 */
class TimestampLabelsTest {

    @Test fun `label wording per source`() {
        assertEquals("Device", TimestampSource.DEVICE.label())
        assertEquals("ZDA", TimestampSource.NMEA_ZDA.label())
        assertEquals("GNSS", TimestampSource.GNSS_PROVIDER.label())
        assertEquals("Unknown", TimestampSource.UNKNOWN.label())
    }

    @Test fun `symbol per source`() {
        assertEquals("🕒", TimestampSource.DEVICE.symbol())
        assertEquals("🛰️", TimestampSource.NMEA_ZDA.symbol())
        assertEquals("📡", TimestampSource.GNSS_PROVIDER.symbol())
        assertEquals("❔", TimestampSource.UNKNOWN.symbol())
    }

    @Test fun `source badge appends label and symbol`() {
        val out = formatTimestampWithSourceBadge(0L, TimestampSource.GNSS_PROVIDER)
        assertTrue("got: $out", out.endsWith(" [GNSS 📡]"))
    }

    @Test fun `source badge without symbol`() {
        val out = formatTimestampWithSourceBadge(0L, TimestampSource.DEVICE, includeSymbol = false)
        assertTrue("got: $out", out.endsWith(" [Device]"))
    }

    @Test fun `compact badge appends only the symbol`() {
        val out = formatTimestampWithCompactBadge(0L, TimestampSource.NMEA_ZDA)
        assertTrue("got: $out", out.endsWith(" 🛰️"))
    }

    @Test fun `unknown source fallback`() {
        assertTrue(formatTimestampWithSourceBadge(0L, TimestampSource.UNKNOWN).endsWith(" [Unknown ❔]"))
    }
}
