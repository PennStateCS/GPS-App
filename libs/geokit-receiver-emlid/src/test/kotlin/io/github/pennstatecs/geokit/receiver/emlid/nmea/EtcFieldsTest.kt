package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ETC decoding against Emlid's documented layout (docs.emlid.com/reachrs4/specifications/nmea-format/ and
 * ETC_rev.3_RS4.pdf, retrieved 2026-11-24).
 */
class EtcFieldsTest {

    private val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true, extraParsers = EmlidNmea.parsers)

    private fun etc(line: String): ETC = registry.parse(line) as? ETC ?: error("not parsed as ETC: $line")

    private fun cs(payload: String): String {
        var x = 0
        for (c in payload) x = x xor c.code
        return "\$" + payload + "*" + "%02X".format(x and 0xFF)
    }

    private fun decode(payload: String): EtcFields = etc(cs(payload)).decoded

    // ── The six examples from the Emlid web page, verbatim with their (valid) checksums ──

    @Test
    fun `doc example - tilt off, no GNSS solution`() {
        val d = etc("\$GNETC,,,,,,,,,*77").decoded
        assertTrue(d.malformedReason, d.isWellFormed)
        assertEquals(EtcStateCode.OFF, d.state)
        assertEquals(EtcNotification.TILT_OFF, d.notification)
        assertNull(d.timeOfFixSecondsOfDay)
        assertNull(d.tiltDeg)
    }

    @Test
    fun `doc example - tilt off, GNSS solution available`() {
        val d = etc("\$GNETC,193828.80,,,,,,,,*58").decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.OFF, d.state)
        assertEquals(19 * 3600 + 38 * 60 + 28.8, d.timeOfFixSecondsOfDay!!, 1e-9)
    }

    @Test
    fun `doc example - setup`() {
        val d = etc("\$GNETC,193828.80,10,00,,,,,,*59").decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.SETUP, d.state)
        assertEquals(EtcNotification.NONE, d.notification)
        assertNull(d.headingDeg)
    }

    @Test
    fun `doc example - alignment`() {
        val d = etc("\$GNETC,193828.80,20,00,,,,,,*5A").decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.ALIGNMENT, d.state)
    }

    @Test
    fun `doc example - compensating, all values`() {
        val d = etc("\$GNETC,193828.80,30,00,172.543,5.621,2.344,1.013,0.993,1.021*5E").decoded
        assertTrue(d.malformedReason, d.isWellFormed)
        assertEquals(EtcStateCode.COMPENSATING, d.state)
        assertEquals(EtcNotification.NONE, d.notification)
        assertEquals(172.543, d.headingDeg!!, 0.0)
        assertEquals(5.621, d.tiltDirectionDeg!!, 0.0)
        assertEquals(2.344, d.tiltDeg!!, 0.0)
        assertEquals(1.013, d.accuracyCoefficientN!!, 0.0)
        assertEquals(0.993, d.accuracyCoefficientE!!, 0.0)
        assertEquals(1.021, d.accuracyCoefficientU!!, 0.0)
    }

    @Test
    fun `doc example - compensating with fast-motion warning`() {
        val d = etc("\$GNETC,193828.80,30,10,172.543,5.621,2.344,1.013,0.993,1.021*5F").decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcNotification.FAST_MOTION, d.notification)
    }

    @Test
    fun `PDF examples with wrong checksums are rejected by the registry`() {
        // ETC_rev.3_RS4.pdf prints different checksums from the web page; most are wrong, one is not hex.
        assertNull(registry.parse("\$GNETC,,,,,,,,,*7E"))
        assertNull(registry.parse("\$GNETC,193828.80,,,,,,,,*6D"))
        assertNull(registry.parse("\$GNETC,193828.80,10,00,,,,,,*3B"))
        assertNull(registry.parse("\$GNETC,193828.80,30,00,172.543,5.621,2.344,1.013,0.993,1.021*4C"))
        assertNull(registry.parse("\$GNETC,193828.80,30,10,172.543,5.621,2.344,1.013,0.993,1.021*2H"))
    }

    @Test
    fun `checksum failure rejects ETC`() {
        val good = cs("GNETC,193828.80,30,00,172.543,5.621,2.344,1.013,0.993,1.021")
        assertNull(registry.parse(good.dropLast(2) + "00"))
    }

    // ── States and notifications ──

    @Test
    fun `fatal error state and every notification code`() {
        assertEquals(EtcStateCode.FATAL_ERROR, decode("GNETC,120000.00,0,30,,,,,,").state)
        assertEquals(EtcNotification.FILTER_FAULT, decode("GNETC,120000.00,0,30,,,,,,").notification)
        assertEquals(EtcNotification.BAD_GNSS, decode("GNETC,120000.00,20,20,,,,,,").notification)
        assertEquals(EtcNotification.FAST_MOTION, decode("GNETC,120000.00,20,10,,,,,,").notification)
        assertTrue(decode("GNETC,120000.00,0,30,,,,,,").isWellFormed)
    }

    @Test
    fun `undocumented state code is unrecognized and malformed`() {
        val d = decode("GNETC,120000.00,40,00,,,,,,")
        assertEquals(EtcStateCode.UNRECOGNIZED, d.state)
        assertEquals("40", d.stateRaw)
        assertFalse(d.isWellFormed)
    }

    @Test
    fun `undocumented notification code is malformed`() {
        val d = decode("GNETC,120000.00,30,15,172.5,5.6,2.3,1.0,1.0,1.0")
        assertEquals(EtcNotification.UNRECOGNIZED, d.notification)
        assertFalse(d.isWellFormed)
        assertNull("values dropped when malformed", d.tiltDeg)
    }

    // ── Malformed / short / empty ──

    @Test
    fun `short sentence is malformed, never throws`() {
        val d = decode("GNETC,120000.00,30")
        assertFalse(d.isWellFormed)
        assertNull(d.tiltDeg)
    }

    @Test
    fun `extra fields are malformed`() {
        assertFalse(decode("GNETC,120000.00,30,00,172.5,5.6,2.3,1.0,1.0,1.0,9").isWellFormed)
    }

    @Test
    fun `compensating without all values is malformed`() {
        assertFalse(decode("GNETC,120000.00,30,00,172.5,5.6,,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,30,00,,,,,,").isWellFormed)
    }

    @Test
    fun `values outside the compensating state are malformed`() {
        assertFalse(decode("GNETC,120000.00,20,00,172.5,5.6,2.3,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,,,172.5,,,,,").isWellFormed)
    }

    @Test
    fun `out-of-range and non-numeric values are malformed`() {
        assertFalse(decode("GNETC,120000.00,30,00,361.0,5.6,2.3,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,30,00,172.5,-0.1,2.3,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,30,00,172.5,5.6,180.5,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,30,00,172.5,5.6,2.3,-1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,30,00,abc,5.6,2.3,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,30,00,NaN,5.6,2.3,1.0,1.0,1.0").isWellFormed)
        assertFalse(decode("GNETC,120000.00,x,00,,,,,,").isWellFormed)
    }

    @Test
    fun `range boundaries are accepted`() {
        val d = decode("GNETC,120000.00,30,00,360.0,0.0,180.0,0.0,0.0,0.0")
        assertTrue(d.malformedReason, d.isWellFormed)
    }

    @Test
    fun `state and notification must agree about tilt being off`() {
        assertFalse(decode("GNETC,120000.00,,00,,,,,,").isWellFormed)
        assertFalse(decode("GNETC,120000.00,20,,,,,,,").isWellFormed)
    }

    @Test
    fun `bad time is malformed but the time-less doc example is fine`() {
        assertFalse(decode("GNETC,256000.00,,,,,,,,").isWellFormed)
        assertFalse(decode("GNETC,12:00:00,,,,,,,,").isWellFormed)
        assertNull(decode("GNETC,256000.00,,,,,,,,").timeOfFixSecondsOfDay)
    }

    @Test
    fun `decoder never throws on junk`() {
        val junk = listOf(
            emptyList(), listOf(""), listOf(",,,"), List(50) { "9".repeat(400) },
            listOf("30", "00", "1e309", "-Infinity", "0x10", " ", "\u0000", "Ω"),
        )
        for (fields in junk) {
            EtcFields.decode(null, fields)
            EtcFields.decode("1e5", fields)
            EtcFields.decode("99999999999999999999.99", fields)
        }
        assertNull(EtcFields.parseTimeOfDay("12345"))
        assertNull(EtcFields.parseTimeOfDay("120000."))
        assertNull(EtcFields.parseTimeOfDay("126000"))
        assertEquals(43200.0, EtcFields.parseTimeOfDay("120000")!!, 0.0)
        assertEquals(86399.99, EtcFields.parseTimeOfDay("235959.99")!!, 1e-9)
    }

    @Test
    fun `raw toString and equality are unchanged by decoding (goldens print them)`() {
        val a = etc(cs("GNETC,173324.80,30,00,217.897,120.426,25.884,8.506,8.506,9.864"))
        assertEquals(
            "ETC(talker=GN, timeRaw=173324.80, dataFields=[30, 00, 217.897, 120.426, 25.884, 8.506, 8.506, 9.864])",
            a.toString(),
        )
        assertEquals(a, etc(cs("GNETC,173324.80,30,00,217.897,120.426,25.884,8.506,8.506,9.864")))
    }

    @Test
    fun `malformed ETC in the fuser is counted and changes no fix`() {
        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(
            provider = Provider.EXTERNAL_TCP,
            registry = DefaultNmeaRegistry.create(verifyChecksum = false, extraParsers = EmlidNmea.parsers),
            onFix = { fixes.add(it) },
            onGsv = { },
        )
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")
        fuser.accept("\$GNETC,123519.00,30")
        fuser.accept("\$GNETC,garbage,,,,")
        assertEquals(1, fixes.size)
        val d = EmlidNmeaDiagnostics.from(fuser.extraSentenceStats)
        assertEquals(2, d.etcCount)
        assertNotNull(d.latestEtc)
        assertFalse(d.latestEtc!!.decoded.isWellFormed)
    }

    // ── Real RS4 captures ──

    private fun etcs(resource: String): List<ETC> =
        (javaClass.getResourceAsStream(resource) ?: error("missing $resource")).bufferedReader().useLines { seq ->
            seq.mapNotNull { registry.parse(it) as? ETC }.toList()
        }

    @Test
    fun `withimu capture - every ETC is a well-formed compensating epoch`() {
        val all = etcs("/nmea/reach_nmea_withimu.txt")
        assertEquals(898, all.size)
        for (e in all) {
            val d = e.decoded
            assertTrue("${e.timeRaw}: ${d.malformedReason}", d.isWellFormed)
            assertEquals(EtcStateCode.COMPENSATING, d.state)
            assertEquals(EtcNotification.NONE, d.notification)
            assertNotNull(d.timeOfFixSecondsOfDay)
        }
        val first = all.first().decoded
        assertEquals(268.660, first.headingDeg!!, 0.0)
        assertEquals(116.146, first.tiltDirectionDeg!!, 0.0)
        assertEquals(17.651, first.tiltDeg!!, 0.0)
        assertEquals(6.280, first.accuracyCoefficientN!!, 0.0)
        assertEquals(6.280, first.accuracyCoefficientE!!, 0.0)
        assertEquals(6.991, first.accuracyCoefficientU!!, 0.0)
        val tilts = all.map { it.decoded.tiltDeg!! }
        assertTrue("tilt range ${tilts.min()}..${tilts.max()}", tilts.all { it in 0.0..180.0 })
    }

    @Test
    fun `plain RS4 capture - every ETC says tilt is off`() {
        val all = etcs("/nmea/reach_nmea_capture.txt")
        assertEquals(885, all.size)
        assertTrue(all.all { it.decoded.isWellFormed && it.decoded.state == EtcStateCode.OFF && it.decoded.timeOfFixSecondsOfDay != null })
    }
}
