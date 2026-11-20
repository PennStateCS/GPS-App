package app.surrealar.gnss.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GnssDebugLogTest {

    private var wasEnabled = false

    @Before fun setUp() { wasEnabled = GnssDebugLog.enabled; GnssDebugLog.resetForTest() }
    @After fun tearDown() { GnssDebugLog.enabled = wasEnabled; GnssDebugLog.resetForTest() }

    @Test fun `every() evaluates the message at most once per interval per key`() {
        GnssDebugLog.enabled = true
        var built = 0
        repeat(5) { GnssDebugLog.every("k1", "test", intervalMs = 60_000L) { built++; "msg" } }
        assertEquals(1, built)
        GnssDebugLog.every("k2", "test", intervalMs = 60_000L) { built++; "other key" }
        assertEquals(2, built)
    }

    @Test fun `nothing is built when disabled (release builds)`() {
        GnssDebugLog.enabled = false
        var built = 0
        GnssDebugLog.event("test") { built++; "x" }
        GnssDebugLog.every("k", "test") { built++; "x" }
        assertEquals(0, built)
    }
}
