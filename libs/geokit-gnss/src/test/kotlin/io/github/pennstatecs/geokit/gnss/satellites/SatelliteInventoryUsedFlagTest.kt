package io.github.pennstatecs.geokit.gnss.satellites

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvEntry
import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteInventoryUsedFlagTest {

    private var now = 0.0
    private val inv = SatelliteInventory(evictionSeconds = 60.0, nowSeconds = { now })

    private fun sat(svid: Int, used: Boolean) = GsvEntry(svid, 40, 100, 35.0, used)

    @Test fun `a satellite that leaves its constellation's GSV list is dropped immediately`() {
        inv.consume(GsvMessage("GP", listOf(sat(1, true), sat(2, true), sat(3, false))))
        now = 1.0
        val snap = inv.consume(GsvMessage("GP", listOf(sat(1, true), sat(3, false))))  // #2 has set
        assertEquals(1, snap.totalUsed)
        assertEquals(2, snap.totalVisible)
    }

    @Test fun `a sparsely reported constellation is not pruned by other constellations`() {
        inv.consume(GsvMessage("GL", listOf(sat(65, true), sat(66, false))))
        now = 20.0   // GLONASS silent for 20 s while GPS keeps updating
        val snap = inv.consume(GsvMessage("GP", listOf(sat(1, true))))
        assertEquals(3, snap.totalVisible)
    }

    @Test fun `another constellation's update does not touch this one`() {
        inv.consume(GsvMessage("GP", listOf(sat(1, true))))
        now = 1.0
        val snap = inv.consume(GsvMessage("GA", listOf(sat(5, true))))
        assertEquals(2, snap.totalUsed)
    }
}
