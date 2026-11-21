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

    // Checksum failures used to log the whole sentence (a position) as a warning on every failure.
    // Now: same tag, sentence id + running count only, and only in debug builds.
    @Test fun `registry checksum failures log the sentence id and count, never the payload`() {
        GnssDebugLog.enabled = true
        val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true)
        val bad = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"
        val missing = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        registry.parse(bad)
        registry.parse(missing)
        assertEquals(
            listOf(Log.DEBUG to "Checksum mismatch: GPGGA (#1 this window)",
                Log.DEBUG to "Missing required checksum: GPGGA (#2 this window)"),
            logs("NmeaRegistry"),
        )
    }

    @Test fun `release builds log nothing for checksum failures`() {
        GnssDebugLog.enabled = false
        val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true)
        registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00")
        assertEquals(emptyList<Pair<Int, String>>(), logs("NmeaRegistry"))
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
