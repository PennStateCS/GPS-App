package app.surrealar.gnss.bus.adapters

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.nmea.parse.*
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
        fuser = NmeaFuser(Provider.RS2_EXTERNAL, registry, onFix = { fixes.add(it) }, onGsv = {})
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
        assertEquals(0.010, fixes[1].hAccM!!, 1e-9)
        assertEquals(RtkStatus.FLOAT, fixes[2].rtkStatus)
        assertEquals("first FLOAT fix must not keep the FIX epoch's ±0.010", 0.300, fixes[2].hAccM!!, 1e-9)
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
        assertEquals(0.050, fixes[0].hAccM!!, 1e-9)
    }

    @Test
    fun `5 Hz GGA with 1 Hz GST - sub-second epochs emit at once and carry the latest GST`() {
        burst("172424.00", q = 4, sigma = 0.010, pdop = 1.2)        // establishes GST cadence
        burst("172425.00", q = 4, sigma = 0.011, pdop = 1.2)
        for (t in listOf("172425.20", "172425.40", "172425.60", "172425.80")) {
            val before = fixes.size
            fuser.accept("\$GNGGA,$t,4118.4448800,N,07601.2345600,W,4,18,0.6,180.0,M,-33.0,M,1.0,0000")
            assertEquals("sub-second GGA $t emits at once (no GST expected)", before + 1, fixes.size)
            assertEquals(0.011, fixes.last().hAccM!!, 1e-9)
        }
        burst("172426.00", q = 4, sigma = 0.012, pdop = 1.2)
        assertEquals(0.012, fixes.last().hAccM!!, 1e-9)
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
