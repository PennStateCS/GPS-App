package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltReading
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFICIAL fixtures: Emlid's published ETC/EBP example sentences, copied verbatim into
 * `src/test/resources/nmea/official/` (see the README there for source URLs and retrieval dates).
 * The existing parsers are tested against them unchanged.
 */
class EmlidOfficialFixturesTest {

    private val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true, extraParsers = EmlidNmea.parsers)

    private fun lines(name: String): List<String> =
        (javaClass.getResourceAsStream("/nmea/official/$name") ?: error("missing official fixture $name"))
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private val webEtc by lazy { lines("emlid_rs4_nmea_format_etc_examples.txt") }

    private fun etc(i: Int): ETC = registry.parse(webEtc[i]) as? ETC ?: error("not parsed: ${webEtc[i]}")

    @Test
    fun `web page has the six documented examples in order`() {
        assertEquals(6, webEtc.size)
    }

    @Test
    fun `OFFICIAL tilt off, no GNSS solution`() {
        val e = etc(0)
        val d = e.decoded
        assertTrue(d.malformedReason, d.isWellFormed)
        assertNull(e.timeRaw)
        assertNull(d.timeOfFixSecondsOfDay)
        assertEquals(EtcStateCode.OFF, d.state)
        assertEquals(EtcNotification.TILT_OFF, d.notification)
        assertEquals(EmlidTiltState.OFF, EmlidTiltReading.from(e).state)
    }

    @Test
    fun `OFFICIAL tilt off, GNSS solution available`() {
        val d = etc(1).decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.OFF, d.state)
        assertEquals(EtcNotification.TILT_OFF, d.notification)
        assertEquals(19 * 3600 + 38 * 60 + 28.8, d.timeOfFixSecondsOfDay!!, 1e-9)
        assertEquals(EmlidTiltState.OFF, EmlidTiltReading.from(etc(1)).state)
    }

    @Test
    fun `OFFICIAL setup`() {
        val d = etc(2).decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.SETUP, d.state)
        assertEquals(EtcNotification.NONE, d.notification)
        assertNull(d.headingDeg); assertNull(d.tiltDeg)
        assertEquals(EmlidTiltState.INITIALIZING, EmlidTiltReading.from(etc(2)).state)
    }

    @Test
    fun `OFFICIAL alignment`() {
        val d = etc(3).decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.ALIGNMENT, d.state)
        assertEquals(EtcNotification.NONE, d.notification)
        assertEquals(EmlidTiltState.ALIGNING, EmlidTiltReading.from(etc(3)).state)
    }

    @Test
    fun `OFFICIAL compensation active`() {
        val d = etc(4).decoded
        assertTrue(d.malformedReason, d.isWellFormed)
        assertEquals(EtcStateCode.COMPENSATING, d.state)
        assertEquals(EtcNotification.NONE, d.notification)
        assertEquals(172.543, d.headingDeg!!, 0.0)
        assertEquals(5.621, d.tiltDirectionDeg!!, 0.0)
        assertEquals(2.344, d.tiltDeg!!, 0.0)
        assertEquals(1.013, d.accuracyCoefficientN!!, 0.0)
        assertEquals(0.993, d.accuracyCoefficientE!!, 0.0)
        assertEquals(1.021, d.accuracyCoefficientU!!, 0.0)
        val r = EmlidTiltReading.from(etc(4))
        assertEquals(EmlidTiltState.ACTIVE, r.state)
        assertFalse(r.hasWarning)
    }

    @Test
    fun `OFFICIAL compensation active with motion warning`() {
        val d = etc(5).decoded
        assertTrue(d.isWellFormed)
        assertEquals(EtcStateCode.COMPENSATING, d.state)
        assertEquals(EtcNotification.FAST_MOTION, d.notification)
        val r = EmlidTiltReading.from(etc(5))
        assertEquals(EmlidTiltState.ACTIVE, r.state)
        assertTrue(r.hasWarning)
    }

    @Test
    fun `OFFICIAL documented state and notification codes`() {
        // nmea-format page: State "" off, 0 fatal error, 10 setup, 20 alignment, 30 compensating.
        assertEquals(listOf(null, 0, 10, 20, 30),
            listOf(EtcStateCode.OFF, EtcStateCode.FATAL_ERROR, EtcStateCode.SETUP, EtcStateCode.ALIGNMENT, EtcStateCode.COMPENSATING).map { it.code })
        // Notification "" tilt is off, 0 none, 10 fast motion, 20 bad gnss, 30 filter fault.
        assertEquals(listOf(null, 0, 10, 20, 30),
            listOf(EtcNotification.TILT_OFF, EtcNotification.NONE, EtcNotification.FAST_MOTION, EtcNotification.BAD_GNSS, EtcNotification.FILTER_FAULT).map { it.code })
    }

    @Test
    fun `OFFICIAL web page checksums are all valid`() {
        for (l in webEtc) assertNotNull(l, registry.parse(l))
    }

    @Test
    fun `OFFICIAL a corrupted official example fails its checksum`() {
        for (l in webEtc) {
            val corrupted = l.replaceFirst("GNETC,", "GNETC,1")
            assertNull(corrupted, registry.parse(corrupted))
        }
    }

    @Test
    fun `OFFICIAL ETC PDF examples carry wrong checksums and are rejected`() {
        val pdf = lines("emlid_etc_rev3_pdf_examples.txt")
        assertEquals(6, pdf.size)
        // Same bodies as the web page; only *5A (alignment) is right. *2H is not hexadecimal.
        val accepted = pdf.filter { registry.parse(it) != null }
        assertEquals(listOf("\$GNETC,193828.80,20,00,,,,,,*5A"), accepted)
        assertEquals(webEtc.map { it.substringBefore('*') }, pdf.map { it.substringBefore('*') })
    }

    @Test
    fun `OFFICIAL EBP example parses with a valid checksum`() {
        val l = lines("emlid_rs4_nmea_format_ebp_example.txt").single()
        val ebp = registry.parse(l) as? EBP
        assertNotNull(ebp)
        assertEquals(48 + 7.038 / 60, ebp!!.baseLat!!, 1e-9)
        assertEquals(11 + 31.0 / 60, ebp.baseLon!!, 1e-9)
        assertEquals(545.440, ebp.baseAltM!!, 0.0)   // documented as "Altitude above ellipsoid in meters"
        assertNull(registry.parse(l.replace("545.440", "545.441")))
    }
}
