package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifecycleLoggerTest {
    private val base = LifecycleLogger.ConfigSummary("portrait", false, 800, 1280, 1.0f, "en-US")

    @Test fun `rotation and dark mode are described`() {
        assertEquals("orientation portrait → landscape, dark mode off → on, size 800x1280dp → 1280x800dp",
            LifecycleLogger.diff(base, base.copy(orientation = "landscape", night = true, widthDp = 1280, heightDp = 800)))
    }

    @Test fun `no listed change, no line`() = assertNull(LifecycleLogger.diff(base, base.copy()))

    @Test fun `trim levels have names`() {
        assertEquals("RUNNING_CRITICAL", LifecycleLogger.trimLevelName(15))
        assertEquals("COMPLETE", LifecycleLogger.trimLevelName(80))
    }
}