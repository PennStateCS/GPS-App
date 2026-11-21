package app.surrealar.coords

import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import app.surrealar.util.DiagnosticsLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CoordDiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val p = ConvertedPosition(
        horizontal = HorizontalSystem.STATE_PLANE, datum = OutputDatum.NAD83_2011, unit = LinearUnit.METERS,
        latDeg = 41.34769, lonDeg = -76.02259, easting = 744_552.97, northing = 132_599.69, zoneLabel = "SPCS 3701",
        vertical = VerticalReference.NAVD88_GEOID18, height = 422.81,
    )

    @Test fun `zone changes and height failures are logged once, without positions`() {
        val dir = tmp.newFolder("logs"); DiagnosticsLogger.initForTest(dir); DiagnosticsLogger.resetThrottles("coord"); CoordDiagnostics.reset()
        repeat(50) { CoordDiagnostics.onResult(p) }
        repeat(50) { CoordDiagnostics.onResult(p.copy(zoneLabel = "SPCS 3702", height = null, heightUnavailableReason = "outside GEOID18 coverage")) }
        DiagnosticsLogger.flush()
        val lines = File(dir, "app-log-current.txt").readLines().filter { " COORD " in it }.map { it.substringAfter("] ") }
        assertEquals(listOf(
            "Coordinate system STATE_PLANE datum=NAD83_2011 zone=SPCS 3701 vertical=NAVD88_GEOID18 unit=METERS",
            "Coordinate system STATE_PLANE datum=NAD83_2011 zone=SPCS 3702 vertical=NAVD88_GEOID18 unit=METERS",
            "Height unavailable for NAVD88_GEOID18: outside GEOID18 coverage",
        ), lines)
        assertFalse(lines.any { it.contains("744") || it.contains("132") || it.contains("41.34") })
    }
}