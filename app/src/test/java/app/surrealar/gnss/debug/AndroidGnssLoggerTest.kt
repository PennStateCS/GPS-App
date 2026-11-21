package app.surrealar.gnss.debug

import android.util.Log
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * geokit-gnss logs through GnssLog; the app installs AndroidGnssLogger. These pin that the logcat
 * output (level, tag, message) is what the code wrote directly before the module existed:
 * `Log.w("NmeaRegistry", …)`, `Log.d("SatelliteInventory", …)`, `DiagnosticsLogger.i("NMEA", …)` and
 * `GnssDebugLog` lines under tag "GnssDebug" with an "[area] " prefix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidGnssLoggerTest {

    private var wasEnabled = false

    @Before fun setUp() {
        wasEnabled = GnssDebugLog.enabled
        GnssDebugLog.resetForTest()
        ShadowLog.clear()
        GnssLog.install(AndroidGnssLogger)
    }

    @After fun tearDown() {
        GnssLog.uninstall()
        GnssDebugLog.enabled = wasEnabled
        GnssDebugLog.resetForTest()
    }

    private fun logs(tag: String) = ShadowLog.getLogsForTag(tag).map { it.type to it.msg }

    @Test fun `registry checksum warnings go to logcat with the same tag and text`() {
        val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true)
        val bad = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"
        val missing = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        registry.parse(bad)
        registry.parse(missing)
        assertEquals(
            listOf(Log.WARN to "Checksum mismatch: $bad", Log.WARN to "Missing required checksum: $missing"),
            logs("NmeaRegistry"),
        )
    }

    @Test fun `diagnostic goes through DiagnosticsLogger (info on logcat)`() {
        GnssLog.diagnostic("NMEA", "parse failures (periodic): 0")
        assertEquals(listOf(Log.INFO to "parse failures (periodic): 0"), logs("NMEA"))
    }

    @Test fun `satellite inventory reset logs at debug level`() {
        SatelliteInventory().reset()
        assertEquals(listOf(Log.DEBUG to "Reset: cleared all satellite state"), logs("SatelliteInventory"))
    }

    @Test fun `fuser debug traces use the GnssDebug tag, area prefix and throttle`() {
        GnssDebugLog.enabled = true
        val fuser = NmeaFuser(
            provider = Provider.EXTERNAL_TCP,
            registry = DefaultNmeaRegistry.create(verifyChecksum = true),
            onFix = { },
            onGsv = { },
        )
        javaClass.getResourceAsStream("/nmea/reach_nmea_capture.txt")!!
            .bufferedReader().useLines { seq -> seq.forEach { fuser.accept(it) } }
        val fixLines = logs(GnssDebugLog.TAG).filter { it.second.startsWith("[fuser] EXTERNAL_TCP fix t=") }
        assertTrue("expected a fuser trace line", fixLines.isNotEmpty())
        assertTrue(fixLines.all { it.first == Log.DEBUG })
        // The capture is shorter than the 15 s throttle, so the per-key summary is written once.
        assertEquals(1, fixLines.size)
    }

    @Test fun `nothing is traced when GnssDebugLog is disabled (release builds)`() {
        GnssDebugLog.enabled = false
        val fuser = NmeaFuser(Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = true), onFix = { }, onGsv = { })
        javaClass.getResourceAsStream("/nmea/reach_nmea_capture.txt")!!
            .bufferedReader().useLines { seq -> seq.forEach { fuser.accept(it) } }
        assertEquals(emptyList<Pair<Int, String>>(), logs(GnssDebugLog.TAG))
    }
}
