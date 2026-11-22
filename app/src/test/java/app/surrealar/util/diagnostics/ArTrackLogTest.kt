package app.surrealar.util.diagnostics

import app.surrealar.util.diagnostics.report.ReportRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArTrackLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun row(fixEnu: DoubleArray? = doubleArrayOf(1.23456789, -2.5, Double.NaN)) = ArTrackRow(
        monoMs = 123456789L, frameNs = 987654321012L, camState = "TRACKING",
        camPos = floatArrayOf(0.123456f, 1.5f, -3.25f), camQuat = floatArrayOf(0f, 0.7071068f, 0f, 0.7071068f),
        earthState = "ENABLED", geoEnu = doubleArrayOf(0.5, 0.25, -1.0), geoHeadingDeg = 271.123456,
        geoHAccM = 3.2, geoVAccM = 1.1, geoYawAccDeg = 12.5,
        fixNew = true, fixAgeMs = 42L, fixUtcMs = 1_763_000_000_000L, provider = "EXTERNAL", rtk = "FIX",
        fixEnu = fixEnu, fixHAccM = 0.014, fixVAccM = 0.02, sats = 28, diffAgeS = 1.0,
        speedMps = 0.8, courseDeg = 90.0, antennaAboveCameraM = 0.134,
    )

    @Test fun `row has one cell per column`() {
        assertEquals(ArTrackLog.COLUMNS.size, row().toCsv().split(",").size)
        assertEquals(ArTrackLog.COLUMNS.size, row(fixEnu = null).toCsv().split(",").size)
    }

    @Test fun `row survives the report redactor unchanged`() {
        val csv = row().toCsv()
        assertEquals(csv, ReportRedactor.scrub(csv))
    }

    @Test fun `missing height is an empty cell`() {
        val cells = row().toCsv().split(",")
        assertEquals("", cells[ArTrackLog.COLUMNS.indexOf("fix_u")])
        assertEquals("1.2346", cells[ArTrackLog.COLUMNS.indexOf("fix_e")])
    }

    @Test fun `file gets a header once and a session marker`() {
        val dir = tmp.newFolder("diag")
        ArTrackLog.startSession(0.134, "Reach RS2+", "SM-X940", dir = dir, nowMs = 0L)
        ArTrackLog.append(row(), dir)
        ArTrackLog.append(row(), dir)
        assertTrue(ArTrackLog.flush())
        val lines = ArTrackLog.file(dir)!!.readLines()
        assertEquals(ArTrackLog.HEADER, lines[0])
        assertTrue(lines[1].startsWith("# session_start"))
        assertEquals(4, lines.size)
        assertEquals(listOf(ArTrackLog.file(dir)), ArTrackLog.files(dir))
    }
}
