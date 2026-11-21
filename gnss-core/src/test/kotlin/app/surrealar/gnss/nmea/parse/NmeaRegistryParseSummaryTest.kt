package app.surrealar.gnss.nmea.parse

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the throttled NMEA parse-failure counters in [NmeaRegistry]. These let an exported
 * diagnostic say whether a receiver stream was parseable or whether sentences were silently dropped.
 * Uses the internal [NmeaRegistry.drainSummaryLine] to read counts deterministically (production
 * emits the same line on a 60s throttle / on stream stop).
 */
class NmeaRegistryParseSummaryTest {

    // Canonical GGA example (valid checksum 0x47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    @Test fun `counts considered, checksum, malformed and unknown-type failures`() {
        val reg = DefaultNmeaRegistry.create(verifyChecksum = true)

        reg.parse(validGga)                  // considered, parses
        reg.parse(validGga)                  // considered, parses
        reg.parse(validGga.dropLast(2) + "00")      // valid body, wrong checksum → checksumFail
        reg.parse("garbage not nmea")        // does not start with $ → malformed
        reg.parse("\$GPXYZ,1,2,3")           // no checksum, unknown tag XYZ → unknownType
        reg.parse("")                        // blank → ignored, NOT counted

        val line = reg.drainSummaryLine("test")!!
        assertTrue(line, line.contains("considered=5"))   // the blank line is excluded
        assertTrue(line, line.contains("checksumFail=1"))
        assertTrue(line, line.contains("malformed=1"))
        assertTrue(line, line.contains("unknownType=1"))
        assertTrue(line, line.contains("recentUnknownTypes=[XYZ]"))
    }

    @Test fun `draining resets the window`() {
        val reg = DefaultNmeaRegistry.create(verifyChecksum = true)
        reg.parse("garbage")                 // malformed=1
        reg.drainSummaryLine("test")         // drains + resets
        val second = reg.drainSummaryLine("test")!!
        assertTrue(second, second.contains("considered=0"))
        assertTrue(second, second.contains("malformed=0"))
    }

    @Test fun `periodic summary is silent when nothing failed`() {
        val reg = DefaultNmeaRegistry.create(verifyChecksum = true)
        reg.parse("")   // blank only — nothing considered, nothing failed
        // A clean window emits nothing for the periodic throttle (avoids log noise).
        assertNull(reg.drainSummaryLine("periodic"))
    }
}
