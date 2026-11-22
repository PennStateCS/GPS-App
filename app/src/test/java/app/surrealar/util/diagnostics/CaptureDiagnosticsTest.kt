package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CaptureDiagnosticsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val policy = AveragingPolicy(minDurationSec = 30, maxDurationSec = 60, minSamples = 100, requiredMinStatus = RtkStatus.FIX, maxDiffAgeSec = 10)

    private fun fix(rtk: RtkStatus, age: Double?) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2345678, lonDeg = -76.1234567, altEllipsoidalM = 312.3, altMslM = null, geoidSeparationM = null,
        hDop = 0.7, vDop = null, pDop = null, hAccM = 0.014, vAccM = 0.02,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 18, satsVisible = 27, diffAgeS = age, speedMps = null, courseDeg = null,
    )

    @Test fun `policy, rejected summary and outcome without coordinates`() {
        val d = CaptureDiagnostics(policy, "EXTERNAL")
        assertEquals("Capture started mode=EXTERNAL minDurationS=30 maxDurationS=60 minFixes=100 requiredRtk=FIX maxCorrAgeS=10", d.startLine())
        repeat(100) { d.onFix(fix(RtkStatus.FIX, 1.0)) }
        repeat(7) { d.onFix(fix(RtkStatus.FLOAT, 1.0)) }
        repeat(3) { d.onFix(fix(RtkStatus.FIX, 15.0)) }
        assertEquals("Capture fix summary received=110 accepted=100 rejected=10: corrections too old=3, rtk below FIX=7",
            d.rejectedSummary(100))
        val line = d.outcomeLine("saved", 100, 34, RtkStatus.FIX, 0.012, 0.025, name = "CP-1")
        assertEquals("Capture saved name=\"CP-1\" mode=EXTERNAL samples=100 duration=34s rtk=FIX hAcc=<2cm vAcc=<5cm rejected=10/110", line)
        assertFalse(line.contains("41.23") || line.contains("76.12"))
    }

    @Test fun `pause and resume are logged once each`() {
        val d = CaptureDiagnostics(policy, "EXTERNAL")
        val lines = mutableListOf<String>()
        val cap = ObservationSession.State.Capturing(Instant.EPOCH, 1, 1, "fix", 0.01, 0.02)
        listOf(cap, ObservationSession.State.Paused("stream stale"), ObservationSession.State.Paused("stream stale"), cap, cap)
            .forEach { d.onState(it) { l, m -> lines += "$l $m" } }
        assertEquals(listOf("W Capture paused: stream stale", "I Capture resumed"), lines)
    }

    @Test fun `captures log is appended and rotated at 1 MB`() {
        val dir = tmp.newFolder("diagnostics")
        File(dir, CaptureLog.FILE).writeText("x".repeat((CaptureLog.MAX_BYTES + 10).toInt()))
        CaptureLog.append("Capture saved name=\"A\"", dir, 0L)
        assertTrue(CaptureLog.flush())
        assertTrue(File(dir, "captures-1.log").exists())
        assertTrue(File(dir, CaptureLog.FILE).readText().trim().endsWith("Capture saved name=\"A\""))
    }

    @Test fun `background appends keep call order`() {
        val dir = tmp.newFolder("diagnostics2")
        repeat(50) { CaptureLog.append("line $it", dir, it.toLong()) }
        assertTrue(CaptureLog.flush())
        val lines = File(dir, CaptureLog.FILE).readLines()
        assertEquals(50, lines.size)
        lines.forEachIndexed { i, l -> assertTrue(l, l.endsWith(" line $i")) }
    }
}