package app.surrealar.gnss.external

import app.surrealar.gnss.external.DiagnosticsLinkLog.char
import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The external link's lines keep going through DiagnosticsLogger, with its throttle keys. */
class DiagnosticsLinkLogTest {

    private val prefix = ReconnectingNmeaAdapter.RETRY_LOG_PREFIX

    @After fun tearDown() = DiagnosticsLogger.resetThrottles(prefix)

    @Test fun `levels map to DiagnosticsLogger level chars`() {
        assertEquals(listOf('D', 'I', 'W', 'E'), LinkLogLevel.entries.map { it.char() })
    }

    @Test fun `logOnChange uses DiagnosticsLogger's throttle, reset by the retry prefix`() {
        val key = prefix + "test.nohost"
        assertTrue(DiagnosticsLinkLog.logOnChange("RECEIVER", key, true, LinkLogLevel.WARN) { "first" })
        assertFalse(DiagnosticsLinkLog.logOnChange("RECEIVER", key, true, LinkLogLevel.WARN) { "repeat" })
        // Shared state: the adapter's reset (through DiagnosticsLogger) re-arms the source's key.
        DiagnosticsLogger.resetThrottles(prefix)
        assertTrue(DiagnosticsLinkLog.logOnChange("RECEIVER", key, true, LinkLogLevel.WARN) { "new outage" })
    }

    @Test fun `every throttles per key`() {
        val key = prefix + "test.summary"
        assertTrue(DiagnosticsLinkLog.every("RECEIVER", key, 60_000L, LinkLogLevel.WARN) { "one" })
        assertFalse(DiagnosticsLinkLog.every("RECEIVER", key, 60_000L, LinkLogLevel.WARN) { "two" })
        DiagnosticsLinkLog.resetThrottles(prefix)
        assertTrue(DiagnosticsLinkLog.every("RECEIVER", key, 60_000L, LinkLogLevel.WARN) { "three" })
    }
}
