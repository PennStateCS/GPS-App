package io.github.pennstatecs.geokit.gnss.nmea.parse

import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error branches of [NmeaRegistry] beyond the checksum / summary tests: non-hex and missing checksums,
 * short tags, a parser returning null, the recent-unknown-types cap, the periodic summary (the window
 * start is moved back via reflection instead of waiting a minute) and the debug lines.
 */
class NmeaRegistryBranchTest {

    private class Recording : GnssLogger {
        val debug = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        override val enabled = true
        override fun d(tag: String, message: String) { debug += message }
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) { diagnostics += message }
        override fun isDue(key: String, intervalMs: Long) = true
        override fun trace(area: String, message: String) {}
    }

    private val log = Recording().also { GnssLog.install(it) }

    @After fun tearDown() = GnssLog.uninstall()

    /** A parser that always fails (returns null). */
    private val failing = object : SentenceParser<NmeaSentence> {
        override val tag = "XYZ"
        override fun parse(talker: String, fields: List<String>): NmeaSentence? = null
    }

    private fun cs(payload: String) = "%02X".format(payload.fold(0) { a, c -> a xor c.code } and 0xFF)
    private fun line(payload: String) = "\$$payload*${cs(payload)}"

    private fun registry(verify: Boolean = true, require: Boolean = false) =
        NmeaRegistry(mapOf("XYZ" to failing), verifyChecksum = verify, requireChecksum = require)

    private fun moveWindowStartBack(r: NmeaRegistry) {
        val f = NmeaRegistry::class.java.getDeclaredField("lastSummaryMs")
        f.isAccessible = true
        f.setLong(r, System.currentTimeMillis() - 61_000L)
    }

    @Test fun nonHexChecksum_isAChecksumFailure() {
        val r = registry()
        assertNull(r.parse("\$GPXYZ,1*ZZ"))
        assertTrue(r.drainSummaryLine("t")!!.contains("checksumFail=1"))
    }

    @Test fun badChecksum_logsSentenceIdOnly() {
        val r = registry()
        assertNull(r.parse("\$GPXYZ,41.0,-76.0*00"))
        assertEquals(listOf("Checksum mismatch: GPXYZ (#1 this window)"), log.debug)
    }

    @Test fun missingRequiredChecksum_isRejected_withUnknownIdWhenTagIsEmpty() {
        val r = registry(require = true)
        assertNull(r.parse("\$GPXYZ,1"))
        assertNull(r.parse("\$,1"))
        assertEquals(listOf("Missing required checksum: GPXYZ (#1 this window)", "Missing required checksum: ? (#2 this window)"), log.debug)
    }

    @Test fun truncatedChecksum_isTreatedAsMissing() {
        // "*A" (one hex digit) is not a checksum: allowed when not required, rejected when required.
        val failingParse = registry().parse("\$GPXYZ,1*A")
        assertNull("the parser itself returns null", failingParse)
        assertTrue(registry().run { parse("\$GPXYZ,1*A"); drainSummaryLine("t")!! }.contains("parseFail=1"))
        assertTrue(registry(require = true).run { parse("\$GPXYZ,1*A"); drainSummaryLine("t")!! }.contains("checksumFail=1"))
    }

    @Test fun noVerification_skipsChecksums() {
        val r = registry(verify = false)
        assertNull(r.parse("\$GPXYZ,1*00"))
        val s = r.drainSummaryLine("t")!!
        assertTrue(s, s.contains("checksumFail=0") && s.contains("parseFail=1"))
    }

    @Test fun malformedShapes_areCounted() {
        val r = registry(verify = false)
        assertNull(r.parse("GPGGA,1"))       // no '$'
        assertNull(r.parse("$*00"))          // empty payload
        assertNull(r.parse("$"))             // nothing after '$'
        assertNull(r.parse("\$GPG,1"))       // tag shorter than talker + 3
        assertNull(r.parse("   "))           // blank: not counted
        val s = r.drainSummaryLine("t")!!
        assertTrue(s, s.contains("considered=4") && s.contains("malformed=4"))
    }

    @Test fun recentUnknownTypes_keepOnlyTheLastEight() {
        val r = registry(verify = false)
        val tags = (0 until 10).map { "Q%02d".format(it) }
        tags.forEach { r.parse("\$GP$it,1") }
        val s = r.drainSummaryLine("stop")!!
        assertTrue(s, s.contains("unknownType=10"))
        assertTrue(s, s.endsWith("recentUnknownTypes=[${tags.drop(2).joinToString(",")}]"))
    }

    @Test fun periodicSummary_isEmittedAfterTheInterval_whenSomethingFailed() {
        val r = registry(verify = false)
        r.parse("\$GPXYZ,1")                  // parse failure in this window
        moveWindowStartBack(r)
        r.parse("\$GPXYZ,2")                  // triggers the periodic summary (counted before)
        assertEquals(1, log.diagnostics.size)
        assertTrue(log.diagnostics[0], log.diagnostics[0].startsWith("NMEA parse summary reason=periodic considered=2"))
    }

    @Test fun periodicSummary_unknownTypesOnly_goToDebug() {
        val r = registry(verify = false)
        r.parse("\$PEMLID,1")
        moveWindowStartBack(r)
        r.parse("\$PEMLID,2")               // summary drains before this line is counted
        assertTrue(log.diagnostics.isEmpty())
        assertTrue(log.debug.toString(), log.debug.any { it.startsWith("unknown sentence types this window: n=1 recent=[MLID]") })
    }

    @Test fun logParseSummary_writesDiagnostic_andNothingWhenEmpty() {
        val r = registry(verify = false)
        r.logParseSummary("idle")
        assertTrue(log.diagnostics.isEmpty())
        r.parse("\$GPXYZ,1")
        r.logParseSummary("source stopped")
        assertEquals(1, log.diagnostics.size)
        assertTrue(log.diagnostics[0].contains("reason=source stopped considered=1 ok=0"))
    }
}
