package io.github.pennstatecs.geokit.gnss.bus.adapters

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.nmea.parse.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Epoch alignment: GST and GSA that follow the GGA in a burst (Emlid order RMC, GGA, GSA, GSV,
 * VTG, GST, ZDA) must enrich THAT epoch's fix, not the next one.
 */
class NmeaFuserEpochTest {

    private val fixes = mutableListOf<Fix>()
    private lateinit var fuser: NmeaFuser

    @Before
    fun setUp() {
        val registry = NmeaRegistry(
            mapOf(
                "GGA" to GgaParser(), "RMC" to RmcParser(), "GSA" to GsaParser(),
                "GSV" to GsvParser(), "ZDA" to ZdaParser(), "GST" to GstParser()
            ),
            verifyChecksum = false
        )
        fixes.clear()
        fuser = NmeaFuser(Provider.EXTERNAL_TCP, registry, onFix = { fixes.add(it) }, onGsv = {})
    }

    /** One Emlid-ordered burst. [q] GGA quality, [sigma] GST lat/lon/alt σ, [pdop] GSA PDOP. */
    private fun burst(t: String, q: Int, sigma: Double, pdop: Double) {
        fuser.accept("\$GNRMC,$t,A,4118.4448800,N,07601.2345600,W,0.01,0.0,201126,,,R")
        fuser.accept("\$GNGGA,$t,4118.4448800,N,07601.2345600,W,$q,18,0.6,180.0,M,-33.0,M,1.0,0000")
        fuser.accept("\$GNGSA,A,3,02,05,07,09,13,15,18,20,,,,,$pdop,0.6,0.9,1")
        fuser.accept("\$GNGST,$t,0.020,,,,$sigma,$sigma,${sigma * 2}")
        fuser.accept("\$GNZDA,$t,20,11,2026,00,00")
    }

    @Test
    fun `GST arriving after GGA enriches the same epoch at a FIX to FLOAT transition`() {
        burst("172423.00", q = 4, sigma = 0.010, pdop = 1.2)   // stream is now known to send GST
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)
        burst("172425.00", q = 5, sigma = 0.300, pdop = 1.2)

        assertEquals(3, fixes.size)
        assertEquals(RtkStatus.FIX, fixes[1].rtkStatus)
        // hAccM is a 68 % radius: 1.515 × per-axis σ (vertical stays 1σ).
        assertEquals(0.010 * 1.515, fixes[1].hAccM!!, 1e-9)
        assertEquals(RtkStatus.FLOAT, fixes[2].rtkStatus)
        assertEquals("first FLOAT fix must not keep the FIX epoch's accuracy", 0.300 * 1.515, fixes[2].hAccM!!, 1e-9)
        assertEquals(0.600, fixes[2].vAccM!!, 1e-9)
    }

    @Test
    fun `GSA after GGA enriches the same epoch`() {
        burst("172423.00", q = 4, sigma = 0.010, pdop = 9.9)
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)
        burst("172425.00", q = 4, sigma = 0.010, pdop = 3.4)
        assertEquals(1.2, fixes[1].pDop!!, 1e-9)
        assertEquals(3.4, fixes[2].pDop!!, 1e-9)
    }

    @Test
    fun `cold start - the first fix before any GST is emitted without waiting`() {
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)
        assertEquals(1, fixes.size)
        assertNull("no GST seen yet when the first GGA arrived", fixes[0].hAccM)
    }

    @Test
    fun `GST from the previous epoch is never attached`() {
        fuser.accept("\$GNGST,172423.00,0.020,,,,0.010,0.010,0.020")
        fuser.accept("\$GNGGA,172424.00,4118.4448800,N,07601.2345600,W,5,18,0.6,180.0,M,-33.0,M,1.0,0000")
        fuser.accept("\$GNRMC,172425.00,A,4118.4448800,N,07601.2345600,W,0.01,0.0,201126,,,R") // next epoch
        assertEquals(1, fixes.size)
        assertNull(fixes[0].hAccM)
    }

    @Test
    fun `GST already received for this time attaches immediately`() {
        fuser.accept("\$GNGST,172424.00,0.020,,,,0.050,0.050,0.100")
        fuser.accept("\$GNGGA,172424.00,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
        assertEquals(1, fixes.size)
        assertEquals(0.050 * 1.515, fixes[0].hAccM!!, 1e-9)
    }

    @Test
    fun `5 Hz GGA with 1 Hz GST - sub-second epochs emit at once and carry the latest GST`() {
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)        // establishes GST cadence
        burst("172425.00", q = 4, sigma = 0.011, pdop = 1.2)
        for (t in listOf("172425.20", "172425.40", "172425.60", "172425.80")) {
            val before = fixes.size
            fuser.accept("\$GNGGA,$t,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
            assertEquals("sub-second GGA $t emits at once (no GST expected)", before + 1, fixes.size)
            assertEquals(0.011 * 1.515, fixes.last().hAccM!!, 1e-9)
        }
        burst("172426.00", q = 4, sigma = 0.012, pdop = 1.2)
        assertEquals(0.012 * 1.515, fixes.last().hAccM!!, 1e-9)
    }

    @Test
    fun `5 Hz - a status change between GSTs drops the carried accuracy`() {
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)
        burst("172425.00", q = 4, sigma = 0.010, pdop = 1.2)
        fuser.accept("\$GNGGA,172425.20,4118.4448800,N,07601.2345600,W,5,18,0.6,180.0,M,-33.0,M,1.0,0000")
        assertEquals(RtkStatus.FLOAT, fixes.last().rtkStatus)
        assertNull("FLOAT fix must not show the FIX epoch's GST", fixes.last().hAccM)
    }

    @Test
    fun `lost GST - held fix is emitted by flushIfStale within 300 ms (fake clock)`() {
        var now = 1_000_000L
        val registry = NmeaRegistry(mapOf("GGA" to GgaParser(), "GST" to GstParser()), verifyChecksum = false)
        val out = mutableListOf<Fix>()
        val f = NmeaFuser(Provider.EXTERNAL_TCP, registry, onFix = { out.add(it) }, onGsv = {}, clock = { now })
        fun gga(t: String) = f.accept("\$GNGGA,$t,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
        gga("172424.00"); f.accept("\$GNGST,172424.00,0.02,,,,0.010,0.010,0.020")
        gga("172425.00"); f.accept("\$GNGST,172425.00,0.02,,,,0.010,0.010,0.020")
        val before = out.size
        now += 1_000; gga("172426.00")                 // expects a GST that never arrives
        assertEquals("held for its GST", before, out.size)
        now += 200; f.flushIfStale()
        assertEquals("not yet stale at 200 ms", before, out.size)
        now += 150; f.flushIfStale()
        assertEquals("emitted once past 300 ms, without waiting for the next epoch", before + 1, out.size)
        f.flushIfStale()
        assertEquals("emitted only once", before + 1, out.size)
    }

    private fun zdaFuser(clock: () -> Long, out: MutableList<Fix>) = NmeaFuser(
        Provider.EXTERNAL_TCP,
        NmeaRegistry(mapOf("GGA" to GgaParser(), "RMC" to RmcParser(), "ZDA" to ZdaParser()), verifyChecksum = false),
        onFix = { out.add(it) }, onGsv = {}, clock = clock
    )

    private fun epochBurst(f: NmeaFuser, t: String, ddmmyy: String, dd: String, mm: String, yyyy: String) {
        f.accept("\$GNRMC,$t,A,4118.4448800,N,07601.2345600,W,0.01,0.0,$ddmmyy,,,R")
        f.accept("\$GNGGA,$t,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
        f.accept("\$GNZDA,$t,$dd,$mm,$yyyy,00,00")
    }

    @Test
    fun `ZDA is accepted by GNSS time even when the device clock is an hour off`() {
        val gnss = java.time.ZonedDateTime.of(2026, 11, 20, 17, 24, 24, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        var now = gnss + 3_600_000L                      // tablet clock 1 h ahead
        val out = mutableListOf<Fix>()
        val f = zdaFuser({ now }, out)
        epochBurst(f, "172424.00", "201126", "20", "11", "2026")
        now += 1_000
        epochBurst(f, "172425.00", "201126", "20", "11", "2026")
        assertEquals(io.github.pennstatecs.geokit.gnss.model.TimestampSource.NMEA_ZDA, out.last().timestampSource)
        assertEquals(gnss + 1_000, out.last().timeUtc.toEpochMilli())
    }

    @Test
    fun `after a clock step the date still rolls over at UTC midnight`() {
        val d0900 = java.time.ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        var now = d0900                                  // clock in sync: ZDA accepted on day D
        val out = mutableListOf<Fix>()
        val f = zdaFuser({ now }, out)
        epochBurst(f, "090000.00", "201126", "20", "11", "2026")
        // Network time steps the tablet clock; 15 h later, just after UTC midnight:
        now = d0900 + 15 * 3_600_000L + 3_600_000L + 1_000
        epochBurst(f, "000001.00", "211126", "21", "11", "2026")
        val day = out.last().timeUtc.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        assertEquals("fix after midnight must be dated the 21st, not a day behind",
            java.time.LocalDate.of(2026, 11, 21), day)
        // And the ZDA from the new day is accepted (matches the GGA's time), so the next epoch uses it.
        now += 1_000
        epochBurst(f, "000002.00", "211126", "21", "11", "2026")
        assertEquals(io.github.pennstatecs.geokit.gnss.model.TimestampSource.NMEA_ZDA, out.last().timestampSource)
        assertEquals(java.time.LocalDate.of(2026, 11, 21), out.last().timeUtc.atZone(java.time.ZoneOffset.UTC).toLocalDate())
    }

    @Test
    fun `stream end - flush emits the held fix`() {
        val now = 1_000_000L   // 0 would read as "no GST ever seen"
        val registry = NmeaRegistry(mapOf("GGA" to GgaParser(), "GST" to GstParser()), verifyChecksum = false)
        val out = mutableListOf<Fix>()
        val f = NmeaFuser(Provider.EXTERNAL_TCP, registry, onFix = { out.add(it) }, onGsv = {}, clock = { now })
        f.accept("\$GNGST,172424.00,0.02,,,,0.010,0.010,0.020")
        f.accept("\$GNGGA,172425.00,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
        val held = out.size
        assertEquals("GGA is waiting for its GST", 0, held)
        f.flush()
        assertEquals(held + 1, out.size)
    }

    @Test
    fun `flush releases a fix still waiting for its GST`() {
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)
        burst("172425.00", q = 4, sigma = 0.010, pdop = 1.2)
        val before = fixes.size
        fuser.accept("\$GNGGA,172426.00,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
        assertEquals("held for its GST", before, fixes.size)
        fuser.flush()
        assertEquals(before + 1, fixes.size)
    }

    @Test
    fun `Galileo prefers the E1 signal (id 7) over E5a (id 1)`() {
        val sky = mutableListOf<GsvMessage>()
        val f = NmeaFuser(Provider.INTERNAL, NmeaRegistry(mapOf("GSV" to GsvParser()), verifyChecksum = false),
            onFix = {}, onGsv = { sky.add(it) })
        f.accept("\$GAGSV,1,1,01,05,40,083,30,1")   // E5a
        f.accept("\$GAGSV,1,1,01,05,40,083,42,7")   // E1
        assertEquals(42.0, sky.last().entries.single().snrDbHz!!, 0.0)
        // Order of arrival must not matter.
        f.accept("\$GAGSV,1,1,01,05,40,083,31,1")
        assertEquals(42.0, sky.last().entries.single().snrDbHz!!, 0.0)
    }

    @Test
    fun `GPS still prefers L1 (id 1) over L5 (id 8)`() {
        val sky = mutableListOf<GsvMessage>()
        val f = NmeaFuser(Provider.INTERNAL, NmeaRegistry(mapOf("GSV" to GsvParser()), verifyChecksum = false),
            onFix = {}, onGsv = { sky.add(it) })
        f.accept("\$GPGSV,1,1,01,05,40,083,33,8")
        f.accept("\$GPGSV,1,1,01,05,40,083,41,1")
        assertEquals(41.0, sky.last().entries.single().snrDbHz!!, 0.0)
    }

    @Test
    fun `stream without GST emits on GGA with no hold (SM-X940)`() {
        fuser.accept("\$GNGGA,172424.00,4118.4448800,N,07601.2345600,W,1,18,0.6,180.0,M,-33.0,M,,")
        assertEquals(1, fixes.size)
        fuser.accept("\$GNGGA,172425.00,4118.4448800,N,07601.2345600,W,1,18,0.6,180.0,M,-33.0,M,,")
        assertEquals(2, fixes.size)
    }
}
