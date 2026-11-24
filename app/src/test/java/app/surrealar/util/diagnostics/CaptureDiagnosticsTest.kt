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
        val d = CaptureDiagnostics(policy, "EXTERNAL", id = "cap9")
        assertEquals("Capture started capture=cap9 mode=EXTERNAL minDurationS=30 maxDurationS=60 minFixes=100 requiredRtk=FIX maxCorrAgeS=10", d.startLine())
        repeat(100) { d.onFix(fix(RtkStatus.FIX, 1.0)) }
        repeat(7) { d.onFix(fix(RtkStatus.FLOAT, 1.0)) }
        repeat(3) { d.onFix(fix(RtkStatus.FIX, 15.0)) }
        assertEquals("Capture fix summary capture=cap9 received=110 accepted=100 rejected=10: corrections too old=3, rtk below FIX=7",
            d.rejectedSummary(100))
        val line = d.outcomeLine("saved", 100, 34, RtkStatus.FIX, 0.012, 0.025, pointId = 42L)
        assertEquals("Capture saved id=42 capture=cap9 mode=EXTERNAL samples=100 duration=34s rtk=FIX hAcc=<2cm vAcc=<5cm rejected=10/110", line)
        assertFalse(line.contains("41.23") || line.contains("76.12"))
    }

    @Test fun `outcome line carries the point id, never the point name`() {
        val d = CaptureDiagnostics(policy, "EXTERNAL")
        val saved = d.outcomeLine("saved", 1, 1, RtkStatus.FIX, 0.01, 0.02, pointId = 7L)
        assertTrue(saved.startsWith("Capture saved id=7 capture=${d.id} "))
        assertFalse(saved.contains("name="))
        val cancelled = d.outcomeLine("cancelled", null, null, null, null, null)
        assertTrue(cancelled.startsWith("Capture cancelled capture=${d.id} mode="))
    }

    @Test fun `pause and resume are logged once each`() {
        val d = CaptureDiagnostics(policy, "EXTERNAL", id = "cap3")
        val lines = mutableListOf<String>()
        val cap = ObservationSession.State.Capturing(Instant.EPOCH, 1, 1, "fix", 0.01, 0.02)
        listOf(cap, ObservationSession.State.Paused("stream stale"), ObservationSession.State.Paused("stream stale"), cap, cap)
            .forEach { d.onState(it) { l, m -> lines += "$l $m" } }
        assertEquals(listOf("W Capture paused: stream stale capture=cap3", "I Capture resumed capture=cap3"), lines)
    }

    @Test fun `one capture id on every line of a capture, a new one per capture`() {
        val a = CaptureDiagnostics(policy, "EXTERNAL")
        val b = CaptureDiagnostics(policy, "EXTERNAL")
        assertTrue(a.id != b.id && a.id.startsWith("cap"))
        val lines = mutableListOf(a.startLine(), a.rejectedSummary(0), a.outcomeLine("completed", 0, 0, null, null, null))
        a.onState(ObservationSession.State.Paused("stream stale")) { _, m -> lines += m }
        assertTrue(lines.all { it.contains("capture=${a.id} ") || it.endsWith("capture=${a.id}") })
    }

    @Test fun `antenna height problems are flagged once per capture, good settings are not`() {
        val s = app.surrealar.gnss.settings.GnssReceiverSettings()
        val d = CaptureDiagnostics(policy, "EXTERNAL", id = "cap5")
        assertEquals(null, d.antennaHeightLine(s, external = true))
        assertEquals(null, d.antennaHeightLine(s, external = false))
        fun reason(line: String?) = line?.let { DiagEvents.parse(it)!!.second.let { f -> f["reason"] + "/" + f["detail"] } }
        assertEquals("MISSING/settingsUnreadable", reason(d.antennaHeightLine(null, external = true)))
        assertEquals("MISSING/poleHeightZero", reason(d.antennaHeightLine(s.copy(poleHeightM = 0.0), external = true)))
        assertEquals("INCONSISTENT/poleHeightOldMeaning", reason(d.antennaHeightLine(s.copy(poleHeightNeedsCheck = true), external = true)))
        assertEquals("INCONSISTENT/poleHeightRange", reason(d.antennaHeightLine(s.copy(poleHeightM = 180.0), external = true)))
        assertEquals("INCONSISTENT/phaseCenterOffsetRange", reason(d.antennaHeightLine(s.copy(phaseCenterOffsetOverrideM = -1.0), external = true)))
        assertEquals("INCONSISTENT/internalHeightRange", reason(d.antennaHeightLine(s.copy(internalAntennaHeightM = -2.0), external = false)))
        assertEquals("internal handheld (0 m) is normal", null, d.antennaHeightLine(s.copy(poleHeightM = 0.0), external = false))
        assertEquals("ANTENNA_HEIGHT reason=MISSING detail=poleHeightZero mode=EXTERNAL capture=cap5 (Capture antenna height setting looks wrong)",
            d.antennaHeightLine(s.copy(poleHeightM = 0.0), external = true))
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