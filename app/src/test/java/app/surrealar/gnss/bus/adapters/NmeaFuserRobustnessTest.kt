package app.surrealar.gnss.bus.adapters

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.nmea.parse.DefaultNmeaRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-device robustness of the fuser: epoch handling, NMEA 4.10 fields, stale enrichments. */
class NmeaFuserRobustnessTest {

    private val fixes = mutableListOf<Fix>()
    private val gsv = mutableListOf<GsvMessage>()
    private val fuser = NmeaFuser(
        Provider.RS2_EXTERNAL,
        DefaultNmeaRegistry.create(verifyChecksum = false),
        onFix = { fixes += it },
        onGsv = { gsv += it }
    )

    private fun gga(time: String, quality: Int = 4, sats: String = "08") =
        "\$GNGGA,$time,4807.038,N,01131.000,E,$quality,$sats,0.9,545.4,M,46.9,M,1.0,0001"

    @Test fun `used-satellite set is rebuilt every epoch instead of accumulating`() {
        fuser.accept(gga("120000.00", sats = "00"))
        fuser.accept("\$GPGSA,A,3,01,02,03,04,05,06,,,,,,,1.5,0.9,1.2")
        fuser.accept(gga("120001.00", sats = "00"))
        // Next epoch: satellites 05/06 left the solution.
        fuser.accept("\$GPGSA,A,3,01,02,03,04,,,,,,,,,1.5,0.9,1.2")
        fuser.accept(gga("120002.00", sats = "00"))
        assertEquals(4, fixes.last().satsUsed)
    }

    @Test fun `GN talker GSA uses NMEA 4_10 system id so equal SVIDs in different constellations both count`() {
        fuser.accept(gga("120000.00", sats = "00"))
        fuser.accept("\$GNGSA,A,3,05,07,,,,,,,,,,,1.5,0.9,1.2,1") // GPS
        fuser.accept("\$GNGSA,A,3,05,07,,,,,,,,,,,1.5,0.9,1.2,3") // Galileo
        fuser.accept(gga("120001.00", sats = "00"))
        assertEquals(4, fixes.last().satsUsed)
    }

    @Test fun `satsUsed takes the larger of GSA and GGA counts`() {
        fuser.accept(gga("120000.00", sats = "00"))
        fuser.accept("\$GPGSA,A,3,01,02,03,,,,,,,,,,1.5,0.9,1.2")
        fuser.accept(gga("120001.00", sats = "22"))
        assertEquals(22, fixes.last().satsUsed)
    }

    @Test fun `quality 0 GGA with coordinates is not published as a fix`() {
        fuser.accept(gga("120000.00", quality = 0))
        assertTrue(fixes.isEmpty())
    }

    @Test fun `quality 6 maps to dead reckoning and 3 to single`() {
        fuser.accept(gga("120000.00", quality = 6))
        assertEquals(RtkStatus.DEAD_RECKONING, fixes.last().rtkStatus)
        fuser.accept(gga("120001.00", quality = 3))
        assertEquals(RtkStatus.SINGLE, fixes.last().rtkStatus)
    }

    @Test fun `GST from an old epoch is not attached to a new fix`() {
        fuser.accept("\$GNGST,120000.00,0.5,,,,0.010,0.012,0.020")
        fuser.accept(gga("120000.00"))
        assertNotNull(fixes.last().hAccM)
        // Receiver stops sending GST; 10 s later the accuracy must not stay frozen.
        fuser.accept(gga("120010.00"))
        assertNull(fixes.last().hAccM)
    }

    @Test fun `RMC speed is applied only near its own epoch`() {
        fuser.accept("\$GNRMC,120000.00,A,4807.038,N,01131.000,E,2.0,90.0,220620,,,A")
        fuser.accept(gga("120000.40"))
        assertNotNull(fixes.last().speedMps)
        fuser.accept(gga("120030.00"))
        assertNull(fixes.last().speedMps)
    }

    @Test fun `multi-signal GSV sequences merge into one list per constellation`() {
        // L1 (signal 1): 3 satellites; L5 (signal 8): 2 of the same satellites.
        fuser.accept("\$GPGSV,1,1,03,01,40,083,46,02,17,308,41,03,07,344,39,1")
        fuser.accept("\$GPGSV,1,1,02,01,40,083,50,03,07,344,33,8")
        val last = gsv.last()
        assertEquals("GP", last.constellation)
        assertEquals(listOf(1, 2, 3), last.entries.map { it.svid })
        // L1 SNR wins (lowest signal id), not the L5 value.
        assertEquals(46.0, last.entries.first { it.svid == 1 }.snrDbHz!!, 0.0)
        // Visible count is the distinct satellites (3), not the last sequence's total (2).
        fuser.accept(gga("120000.00", sats = "03"))
        assertEquals(3, fixes.last().satsVisible)
    }

    @Test fun `legacy single-sequence GSV keeps the sentence total`() {
        fuser.accept("\$GPGSV,2,1,05,01,40,083,46,02,17,308,41,03,07,344,39,04,50,100,40")
        fuser.accept("\$GPGSV,2,2,05,05,30,200,35")
        assertEquals(5, gsv.last().entries.size)
        fuser.accept(gga("120000.00", sats = "05"))
        assertEquals(5, fixes.last().satsVisible)
    }
}
