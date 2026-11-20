package app.surrealar.gnss.satellites

import app.surrealar.gnss.bus.adapters.GsvEntry
import app.surrealar.gnss.bus.adapters.GsvMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteInventoryUsedFlagTest {

    private var now = 0.0
    private val inv = SatelliteInventory(evictionSeconds = 60.0, nowSeconds = { now })

    private fun sat(svid: Int, used: Boolean) = GsvEntry(svid, 40, 100, 35.0, used)

    @Test fun `a satellite that leaves the GSV list stops counting as used immediately but stays visible`() {
        inv.consume(GsvMessage("GP", listOf(sat(1, true), sat(2, true), sat(3, false))))
        now = 1.0
        val snap = inv.consume(GsvMessage("GP", listOf(sat(1, true), sat(3, false))))  // #2 has set
        assertEquals(1, snap.totalUsed)
        assertEquals(3, snap.totalVisible)   // #2 still listed until eviction (no flicker)
    }

    @Test fun `another constellation's update does not touch this one`() {
        inv.consume(GsvMessage("GP", listOf(sat(1, true))))
        now = 1.0
        val snap = inv.consume(GsvMessage("GA", listOf(sat(5, true))))
        assertEquals(2, snap.totalUsed)
    }
}
