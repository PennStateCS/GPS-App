package app.surrealar

import app.surrealar.util.diagnostics.GnssFeedLogger
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Application.onCreate must start the GNSS feed logger after the settings exist (it once ran first and failed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupOrderTest {
    @Test fun `the GNSS feed logger is running after startup`() {
        assertNotNull("GnssFeedLogger.start() failed during Application.onCreate", GnssFeedLogger.instance)
    }
}