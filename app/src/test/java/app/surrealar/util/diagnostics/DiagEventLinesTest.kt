package app.surrealar.util.diagnostics

import app.surrealar.gnss.external.DiagnosticsLinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DiagEvents line builders and the pure AR/GNSS transition helpers behind the coded lines. */
class DiagEventLinesTest {

    @Test fun `DiagLine round-trips through DiagEvents parse, drops nulls, keeps the old sentence last`() {
        val line = DiagLine.of(DiagEvents.RECEIVER_LINK, "state" to "NO_DATA", "conn" to "c3", "afterS" to 10L,
            "reason" to null, extra = "attempts=2", text = "External receiver TCP connected but sent no NMEA")
        assertEquals("RECEIVER_LINK state=NO_DATA conn=c3 afterS=10 attempts=2 (External receiver TCP connected but sent no NMEA)", line)
        val (code, f) = DiagEvents.parse(line)!!
        assertEquals(DiagEvents.RECEIVER_LINK, code)
        assertEquals(mapOf("state" to "NO_DATA", "conn" to "c3", "afterS" to "10", "attempts" to "2"), f)
        assertEquals("spaces never split a value", "a_b", DiagEvents.parse(DiagLine.of(DiagEvents.AR_STATE, "reason" to "a b"))!!.second["reason"])
        assertEquals("blank reads unknown", "unknown", DiagEvents.parse(DiagLine.of(DiagEvents.AR_STATE, "reason" to " "))!!.second["reason"])
    }

    @Test fun `ids are short, per prefix and never repeat`() {
        val a = DiagIds.next(DiagIds.AR); val b = DiagIds.next(DiagIds.AR)
        assertTrue(a.matches(Regex("a\\d+")))
        assertNotEquals(a, b)
        assertTrue(DiagIds.next(DiagIds.OP).matches(Regex("op\\d+")))
        assertTrue(DiagIds.next(DiagIds.CONN).matches(Regex("c\\d+")))
    }

    @Test fun `data operation lines share one op id`() {
        val id = DiagIds.next(DiagIds.OP)
        val lines = listOf(DataOpLog.startLine("Backup import", "json/merge", id),
            DataOpLog.doneLine("Backup import", "json/merge", 4, 20, id),
            DataOpLog.failLine("Backup import", "json/merge", 20, IllegalStateException("x"), id))
        assertTrue(lines.all { it.contains(" op=$id ") })
    }

    @Test fun `AR unavailable reasons`() {
        assertEquals("UNSUPPORTED", ArDiag.availabilityReason("UNSUPPORTED_DEVICE_NOT_CAPABLE"))
        assertEquals("UPDATE_REQUIRED", ArDiag.availabilityReason("SUPPORTED_APK_TOO_OLD"))
        assertEquals("NOT_INSTALLED", ArDiag.availabilityReason("SUPPORTED_NOT_INSTALLED"))
        assertEquals("ERROR", ArDiag.sessionErrorReason(IllegalStateException("/storage/emulated/0/Jane Doe")))
        assertEquals("unknown", ArDiag.sessionErrorReason(null))
        assertEquals("POOR", ArDiag.geoLevel("LOW")); assertNull(ArDiag.geoLevel("NOT_READY"))
        assertEquals("MIXED", ArDiag.anchorKind(2, 1)); assertEquals("TERRAIN", ArDiag.anchorKind(0, 3)); assertEquals("WGS84", ArDiag.anchorKind(3, 0))
        assertEquals(12L, ArDiag.afterS(1_000L, 13_500L)); assertNull(ArDiag.afterS(0L, 13_500L))
    }

    @Test fun `hidden model gate logs a held count once, ignores flicker, baseline zero is silent`() {
        val g = HiddenModelGate("OFFSCREEN", holdMs = 3_000L)
        val out = mutableListOf<String>()
        var now = 0L
        fun frames(count: Int, ms: Long) { val end = now + ms; while (now < end) { now += 33; g.update(count, now, "a4")?.let { out += it.second } } }
        frames(0, 5_000)                // nothing hidden: silent
        frames(2, 1_000); frames(0, 1_000); frames(2, 1_000)   // flicker shorter than the hold
        assertTrue(out.isEmpty())
        frames(2, 10_000)
        frames(0, 10_000)
        assertEquals(listOf("AR_MODEL_HIDDEN reason=OFFSCREEN count=2 ar=a4", "AR models no longer hidden reason=OFFSCREEN ar=a4"), out)
        assertEquals("OFFSCREEN", DiagEvents.parse(out[0])!!.second["reason"])
    }

    @Test fun `alignment jump only when the AR-GNSS offset moves past the threshold`() {
        val d = AlignmentJumpDetector(thresholdM = 2.0)
        assertNull(d.update(1.0, 90.0, 0.1))     // first sample: baseline
        assertNull(d.update(1.3, 95.0, 0.2))     // noise
        val j = d.update(4.0, 270.0, 1.2)!!      // offset flipped sides: ~5.3 m move
        assertTrue(j.deltaM > 5.0)
        assertEquals(1.0, j.deltaVertM!!, 1e-9)
        assertNull(d.update(4.2, 268.0, 1.2))
        assertNull(d.update(Double.NaN, 0.0, null))
    }

    @Test fun `source mismatch logged when it starts or changes, resolved once`() {
        val m = SourceMismatchTracker()
        assertNull(m.onStable("EXTERNAL", "EXTERNAL_TCP", null))
        val start = m.onStable("EXTERNAL", "INTERNAL", "external_unavailable_or_connecting", "c2")!!
        assertEquals('W', start.first)
        assertEquals(mapOf("selected" to "EXTERNAL", "active" to "INTERNAL", "reason" to "external_unavailable_or_connecting", "conn" to "c2"),
            DiagEvents.parse(start.second)!!.second)
        assertNull("same mismatch again", m.onStable("EXTERNAL", "INTERNAL", "external_unavailable_or_connecting"))
        assertEquals("GNSS source mismatch resolved selected=EXTERNAL active=EXTERNAL_TCP", m.onStable("EXTERNAL", "EXTERNAL_TCP", null)!!.second)
        assertNull(m.onStable("EXTERNAL", "EXTERNAL_TCP", null))
    }

    @Test fun `coded lines carry hashes, never names, file names or coordinates`() {
        val name = "Smith Residence"; val file = "/storage/emulated/0/Download/JaneDoe-house.glb"
        val lines = listOf(
            DiagLine.of(DiagEvents.MODEL_MISSING_FILE, "ref" to LogPrivacy.file(file), "ar" to "a1", text = "Model skipped: file missing"),
            DiagLine.of(DiagEvents.MODEL_LOAD, "result" to "FAILED", "reason" to LogPrivacy.errorType(java.io.FileNotFoundException(file)),
                "op" to "op3", extra = "key=17 file=${LogPrivacy.file(file)}", text = "Model preload failed"),
            DiagLine.of(DiagEvents.AR_ANCHOR, "result" to "FAILED", "kind" to "TERRAIN", "reason" to "ERROR_INTERNAL",
                extra = "coordinateId=17 nameRef=${LogPrivacy.ref(name)}", text = "Terrain anchor resolve failed"),
        )
        lines.forEach { l ->
            assertFalse(l, l.contains("Smith") || l.contains("JaneDoe") || l.contains("/storage") || l.contains("41.2"))
            assertTrue(l, DiagEvents.parse(l) != null)
        }
    }

    @Test fun `link log tells the tracker about a connected socket, nothing else`() {
        var calls = 0
        DiagnosticsLinkLog.onSocketConnected = { calls++ }
        try {
            DiagnosticsLinkLog.logOnChange("RECEIVER", "rx.retry.connecting", "h:1", LinkLogLevel.INFO, null) { "x" }
            DiagnosticsLinkLog.logOnChange("RECEIVER", DiagnosticsLinkLog.SOCKET_KEY, "h:1", LinkLogLevel.INFO, null) { "x" }
            DiagnosticsLinkLog.logOnChange("RECEIVER", DiagnosticsLinkLog.SOCKET_KEY, "h:1", LinkLogLevel.INFO, null) { "x" }
            assertEquals("every connect, even when the line itself is throttled", 2, calls)
        } finally {
            DiagnosticsLinkLog.onSocketConnected = null
            app.surrealar.util.DiagnosticsLogger.resetThrottles("rx.retry.")
        }
    }
}
